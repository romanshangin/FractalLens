package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.GradientPalette;
import com.shangin.fractal.render.AntialiasSampleCache;
import com.shangin.fractal.render.BaseColorPhaseCache;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * One synchronous compute operation at a time, invoked only under runtime ownership.
 * Host-visible persistent buffers use Metal unified memory on Apple Silicon; coherent
 * memory is preferred and non-coherent heaps are explicitly flushed/invalidated.
 * Keeps one base/AA/palette generation resident, with a bounded memory budget.
 */
final class VulkanPaletteKernel implements AutoCloseable {
    private static final int TABLE_WORDS = 65536 + 256 + 65536;
    private static final int LOCAL_SIZE = 64;
    private final VkDevice device;
    private final VkPhysicalDevice physical;
    private final VkQueue queue;
    private final int family;
    private final Buffer[] buffers = new Buffer[4];
    private final long budget = Long.getLong("fractal.gpu.palette.maxBytes", 256L * 1024 * 1024);
    private long maxStorage;
    private int maxGroupsX, maxGroupsY;
    private long descriptorLayout, descriptorPool, descriptorSet, pipelineLayout, pipeline, commandPool, fence;
    private VkCommandBuffer command;
    private BaseColorPhaseCache residentBase;
    private AntialiasSampleCache.Snapshot residentAa;
    private GradientPalette residentPalette;

    private VulkanPaletteKernel(VkDevice device, VkPhysicalDevice physical, VkQueue queue, int family) {
        this.device = device;
        this.physical = physical;
        this.queue = queue;
        this.family = family;
    }

    static VulkanPaletteKernel open(VkDevice device, VkPhysicalDevice physical, VkQueue queue, int family) {
        VulkanPaletteKernel kernel = new VulkanPaletteKernel(device, physical, queue, family);
        try {
            kernel.initialize();
            return kernel;
        } catch (RuntimeException | LinkageError failure) {
            kernel.close();
            throw failure;
        }
    }

    private void initialize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, properties);
            VkPhysicalDeviceLimits limits = properties.limits();
            maxStorage = Integer.toUnsignedLong(limits.maxStorageBufferRange());
            maxGroupsX = limits.maxComputeWorkGroupCount(0);
            maxGroupsY = limits.maxComputeWorkGroupCount(1);
            if (limits.maxComputeWorkGroupInvocations() < LOCAL_SIZE
                    || limits.maxComputeWorkGroupSize(0) < LOCAL_SIZE
                    || limits.maxPerStageDescriptorStorageBuffers() < 4
                    || limits.maxDescriptorSetStorageBuffers() < 4 || limits.maxPushConstantsSize() < 32) {
                throw new GpuException("Palette kernel exceeds compute limits", false);
            }
            LongBuffer handle = stack.callocLong(1);
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);
            for (int i = 0; i < 4; i++) {
                bindings.get(i).binding(i).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings), null, handle), "descriptor layout");
            descriptorLayout = handle.get(0);
            VkPushConstantRange.Buffer ranges = VkPushConstantRange.calloc(1, stack);
            ranges.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(32);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(ranges), null, handle),
                    "pipeline layout");
            pipelineLayout = handle.get(0);
            createPipeline(stack);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(4);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(poolSizes), null, handle), "descriptor pool");
            descriptorPool = handle.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout)), handle),
                    "descriptor set");
            descriptorSet = handle.get(0);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT),
                    null, handle), "command pool");
            commandPool = handle.get(0);
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1),
                    pointer), "command buffer");
            command = new VkCommandBuffer(pointer.get(0), device);
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle), "fence");
            fence = handle.get(0);
        }
    }

    private void createPipeline(MemoryStack stack) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) throw new GpuException("Cannot initialize shaderc", false);
        long result = 0, module = 0;
        try {
            String source;
            try (var input = VulkanPaletteKernel.class.getResourceAsStream("palette.comp")) {
                if (input == null) throw new GpuException("Missing palette.comp resource", false);
                source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new GpuException("Cannot read palette shader: " + failure.getMessage(), false);
            }
            result = shaderc_compile_into_spv(compiler, source, shaderc_compute_shader,
                    "palette.comp", "main", 0);
            if (result == 0 || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new GpuException("Palette shader compilation failed: "
                        + (result == 0 ? "no result" : shaderc_result_get_error_message(result)), false);
            }
            ByteBuffer code = CompiledShader.requireBytes(
                    shaderc_result_get_bytes(result), "palette.comp");
            LongBuffer handle = stack.callocLong(1);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default()
                    .pCode(code), null, handle), "shader module");
            module = handle.get(0);
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().layout(pipelineLayout);
            info.get(0).stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module).pName(stack.UTF8("main"));
            handle.put(0, 0);
            int status = vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, handle);
            pipeline = handle.get(0); // A partial creation still needs destruction on failure.
            check(status, "compute pipeline");
        } finally {
            if (module != 0) vkDestroyShaderModule(device, module, null);
            if (result != 0) shaderc_result_release(result);
            shaderc_compiler_release(compiler);
        }
    }

    PaletteRecolorTiming recolor(PaletteRecolorRequest request) throws InterruptedException {
        long started = System.nanoTime();
        int[] words = {request.base().gpuWordCount(), Math.max(1, request.aa().gpuWordCount()),
                TABLE_WORDS, request.colors().length};
        long totalBytes = 0;
        for (int count : words) {
            long bytes = (long) count * 4;
            if (count < 1 || bytes > maxStorage || bytes > Integer.MAX_VALUE) {
                throw new GpuException("Palette buffer exceeds storage/mapping limits", false);
            }
            totalBytes += bytes;
        }
        if (budget <= 0 || totalBytes > budget) throw new GpuException("Palette memory budget exceeded", false);
        // Release resized allocations before creating any replacements, bounding peak residency.
        for (int i = 0; i < 4; i++) {
            if (buffers[i] != null && buffers[i].bytes != words[i] * 4) {
                buffers[i].close();
                buffers[i] = null;
                if (i == 0) residentBase = null;
                if (i == 1) residentAa = null;
                if (i == 2) residentPalette = null;
            }
        }
        long allocationBytes = 0;
        for (int i = 0; i < 4; i++) {
            ensureBuffer(i, words[i] * 4);
            allocationBytes += buffers[i].allocationBytes;
        }
        if (allocationBytes > budget) throw new GpuException("Palette allocations exceed memory budget", false);
        long allocated = System.nanoTime();
        long uploaded = 0;
        if (residentBase != request.base()) {
            request.base().writeGpuWords(buffers[0].ints());
            buffers[0].flush();
            residentBase = request.base();
            uploaded += (long) words[0] * 4;
        }
        if (residentAa != request.aa()) {
            if (request.aa().size() == 0) buffers[1].ints().put(0);
            else request.aa().writeGpuWords(buffers[1].ints());
            buffers[1].flush();
            residentAa = request.aa();
            uploaded += (long) words[1] * 4;
        }
        if (residentPalette != request.palette()) {
            writeTables(request.palette(), buffers[2].ints());
            buffers[2].flush();
            residentPalette = request.palette();
            uploaded += (long) TABLE_WORDS * 4;
        }
        long uploadedAt = System.nanoTime();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Recolor cancelled before submit");
        dispatch(request);
        long dispatchedAt = System.nanoTime();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Recolor cancelled after fence");
        buffers[3].invalidate();
        buffers[3].ints().get(request.colors());
        long done = System.nanoTime();
        return new PaletteRecolorTiming(allocated - started, uploadedAt - allocated,
                dispatchedAt - uploadedAt, done - dispatchedAt, done - started, 0, 0, uploaded, true);
    }

    private void ensureBuffer(int index, int bytes) {
        if (buffers[index] != null && buffers[index].bytes == bytes) return;
        if (buffers[index] != null) {
            buffers[index].close();
            buffers[index] = null;
        }
        if (index == 0) residentBase = null;
        if (index == 1) residentAa = null;
        if (index == 2) residentPalette = null;
        buffers[index] = new Buffer(bytes);
    }

    private void dispatch(PaletteRecolorRequest request) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
            for (int i = 0; i < 4; i++) {
                VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
                info.get(0).buffer(buffers[i].handle).offset(0).range(buffers[i].bytes);
                writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(info);
            }
            vkUpdateDescriptorSets(device, writes, null);
            check(vkResetCommandBuffer(command, 0), "reset command");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin command");
            vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                    stack.longs(descriptorSet), null);
            PaletteOffset o = request.offset();
            ByteBuffer constants = stack.malloc(32);
            constants.asIntBuffer().put(new int[]{request.colors().length, request.aa().size(), 0,
                    o.whole(), o.ascendingBias(), o.ascendingLast(), o.descendingBias(), o.descendingLast()});
            vkCmdPushConstants(command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, constants);
            dispatchCount(request.colors().length);
            if (request.aa().size() > 0) {
                barrier(stack, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                constants.putInt(8, 1);
                vkCmdPushConstants(command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, constants);
                dispatchCount(request.aa().size());
            }
            barrier(stack, VK_ACCESS_HOST_READ_BIT, VK_PIPELINE_STAGE_HOST_BIT);
            check(vkEndCommandBuffer(command), "end command");
            check(vkResetFences(device, fence), "reset fence");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(command.address()));
            check(vkQueueSubmit(queue, submit, fence), "submit palette");
            // Never abandon a live submission, even on cancellation: drain before reuse/destruction.
            int status;
            do {
                status = vkWaitForFences(device, fence, true, 10_000_000L);
            } while (status == VK_TIMEOUT);
            check(status, "wait palette fence");
        }
    }

    private void dispatchCount(int count) {
        long groups = ((long) count + LOCAL_SIZE - 1) / LOCAL_SIZE;
        int x = (int) Math.min(groups, maxGroupsX);
        long y = (groups + x - 1) / x;
        if (y > maxGroupsY) throw new GpuException("Palette dispatch exceeds group-count limits", false);
        vkCmdDispatch(command, x, (int) y, 1);
    }

    private void barrier(MemoryStack stack, int destinationAccess, int destinationStage) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(destinationAccess);
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, destinationStage, 0,
                barrier, null, null);
    }

    private static void writeTables(GradientPalette palette, IntBuffer target) {
        palette.writeLookup(target);
        for (int c = 0; c < 256; c++) {
            double srgb = c / 255.0;
            float linear = (float) (srgb <= 0.04045 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4));
            target.put(Float.floatToRawIntBits(linear));
        }
        for (int i = 0; i < 65536; i++) {
            double linear = i / 65535.0;
            double srgb = linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
            target.put(Math.clamp((int) Math.round(srgb * 255.0), 0, 255));
        }
    }

    @Override
    public void close() {
        // The runtime/session drains healthy work first; lost devices are not waited on.
        for (int i = 0; i < buffers.length; i++) {
            if (buffers[i] != null) { buffers[i].close(); buffers[i] = null; }
        }
        if (fence != 0) { vkDestroyFence(device, fence, null); fence = 0; }
        if (commandPool != 0) { vkDestroyCommandPool(device, commandPool, null); commandPool = 0; }
        if (pipeline != 0) { vkDestroyPipeline(device, pipeline, null); pipeline = 0; }
        if (pipelineLayout != 0) { vkDestroyPipelineLayout(device, pipelineLayout, null); pipelineLayout = 0; }
        if (descriptorPool != 0) { vkDestroyDescriptorPool(device, descriptorPool, null); descriptorPool = 0; }
        if (descriptorLayout != 0) { vkDestroyDescriptorSetLayout(device, descriptorLayout, null); descriptorLayout = 0; }
        residentBase = null; residentAa = null; residentPalette = null;
    }

    private final class Buffer implements AutoCloseable {
        private final int bytes;
        private long handle, memory, allocationBytes;
        private ByteBuffer mapped;
        private boolean coherent;

        Buffer(int bytes) {
            this.bytes = bytes;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer out = stack.callocLong(1);
                check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
                        .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE),
                        null, out), "create buffer");
                handle = out.get(0);
                VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
                vkGetBufferMemoryRequirements(device, handle, requirements);
                allocationBytes = requirements.size();
                long residentBytes = allocationBytes;
                for (Buffer buffer : buffers) {
                    if (buffer != null) residentBytes += buffer.allocationBytes;
                }
                if (residentBytes > budget) throw new GpuException("Palette allocation budget exceeded", false);
                VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.calloc(stack);
                vkGetPhysicalDeviceMemoryProperties(physical, props);
                int selected = -1;
                for (int i = 0; i < props.memoryTypeCount(); i++) {
                    int flags = props.memoryTypes(i).propertyFlags();
                    if ((requirements.memoryTypeBits() & (1 << i)) != 0
                            && (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0) {
                        selected = i;
                        if ((flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0) break;
                    }
                }
                if (selected < 0) throw new GpuException("No host-visible palette memory", false);
                coherent = (props.memoryTypes(selected).propertyFlags() & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
                check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                        .allocationSize(allocationBytes).memoryTypeIndex(selected), null, out), "allocate memory");
                memory = out.get(0);
                check(vkBindBufferMemory(device, handle, memory, 0), "bind memory");
                PointerBuffer pointer = stack.mallocPointer(1);
                check(vkMapMemory(device, memory, 0, VK_WHOLE_SIZE, 0, pointer), "map memory");
                mapped = MemoryUtil.memByteBuffer(pointer.get(0), bytes).order(ByteOrder.nativeOrder());
            } catch (RuntimeException | LinkageError failure) {
                close();
                throw failure;
            }
        }

        IntBuffer ints() { return mapped.asIntBuffer(); }
        void flush() { if (!coherent) synchronizeMemory(true); }
        void invalidate() { if (!coherent) synchronizeMemory(false); }

        private void synchronizeMemory(boolean flush) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkMappedMemoryRange.Buffer range = VkMappedMemoryRange.calloc(1, stack);
                range.get(0).sType$Default().memory(memory).offset(0).size(VK_WHOLE_SIZE);
                check(flush ? vkFlushMappedMemoryRanges(device, range) : vkInvalidateMappedMemoryRanges(device, range),
                        flush ? "flush memory" : "invalidate memory");
            }
        }

        @Override
        public void close() {
            if (mapped != null) { vkUnmapMemory(device, memory); mapped = null; }
            if (handle != 0) { vkDestroyBuffer(device, handle, null); handle = 0; }
            if (memory != 0) { vkFreeMemory(device, memory, null); memory = 0; }
        }
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new GpuException(operation + " failed with VkResult " + result,
                result == VK_ERROR_DEVICE_LOST);
    }
}
