package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.GradientPalette;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.nio.*;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Approximate whole-frame Julia preview: one dispatch calculates and colors
 * every pixel, and only ARGB crosses back to the host. Owned and serialized by
 * the GPU runtime; it never participates in certified backend selection.
 */
final class VulkanJuliaPreviewKernel implements AutoCloseable {
    private static final int LOCAL_SIZE = 8;
    private static final int DESCRIPTORS = 3;
    private static final int PUSH_CONSTANT_BYTES = 48;
    private static final int PALETTE_WORDS = 65_536;
    private final VkDevice device;
    private final VkPhysicalDevice physical;
    private final VkQueue queue;
    private final int family;
    private final Buffer[] buffers = new Buffer[DESCRIPTORS];
    private final long budget = Long.getLong("fractal.gpu.juliaPreview.maxBytes", 128L * 1024 * 1024);
    private long maxStorage;
    private int maxGroupsX, maxGroupsY;
    private long descriptorLayout, descriptorPool, descriptorSet, pipelineLayout, pipeline, commandPool, fence;
    private VkCommandBuffer command;
    private boolean closed, deviceLost;
    private GradientPalette residentPalette;

    private VulkanJuliaPreviewKernel(VkDevice device, VkPhysicalDevice physical, VkQueue queue, int family) {
        this.device = device;
        this.physical = physical;
        this.queue = queue;
        this.family = family;
    }

    static VulkanJuliaPreviewKernel open(VkDevice device, VkPhysicalDevice physical, VkQueue queue, int family) {
        var kernel = new VulkanJuliaPreviewKernel(device, physical, queue, family);
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
            if (limits.maxComputeWorkGroupInvocations() < LOCAL_SIZE * LOCAL_SIZE
                    || limits.maxComputeWorkGroupSize(0) < LOCAL_SIZE
                    || limits.maxComputeWorkGroupSize(1) < LOCAL_SIZE
                    || limits.maxPerStageDescriptorStorageBuffers() < DESCRIPTORS
                    || limits.maxDescriptorSetStorageBuffers() < DESCRIPTORS
                    || limits.maxPushConstantsSize() < PUSH_CONSTANT_BYTES) {
                throw new GpuException("Julia preview exceeds compute limits", false);
            }
            LongBuffer handle = stack.callocLong(1);
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(DESCRIPTORS, stack);
            for (int i = 0; i < DESCRIPTORS; i++) {
                bindings.get(i).binding(i).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings), null, handle), "preview descriptor layout");
            descriptorLayout = handle.get(0);
            VkPushConstantRange.Buffer ranges = VkPushConstantRange.calloc(1, stack);
            ranges.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_CONSTANT_BYTES);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(ranges), null, handle),
                    "preview pipeline layout");
            pipelineLayout = handle.get(0);
            createPipeline(stack);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(DESCRIPTORS);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(poolSizes), null, handle), "preview descriptor pool");
            descriptorPool = handle.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout)), handle),
                    "preview descriptor set");
            descriptorSet = handle.get(0);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT),
                    null, handle), "preview command pool");
            commandPool = handle.get(0);
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1),
                    pointer), "preview command buffer");
            command = new VkCommandBuffer(pointer.get(0), device);
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle),
                    "preview fence");
            fence = handle.get(0);
        }
    }

    private void createPipeline(MemoryStack stack) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) throw new GpuException("Cannot initialize shaderc", false);
        long result = 0, module = 0;
        try {
            String source;
            try (var input = VulkanJuliaPreviewKernel.class.getResourceAsStream("julia-preview.comp")) {
                if (input == null) throw new GpuException("Missing julia-preview.comp resource", false);
                source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new GpuException("Cannot read Julia preview shader: " + failure.getMessage(), false);
            }
            result = shaderc_compile_into_spv(compiler, source, shaderc_compute_shader,
                    "julia-preview.comp", "main", 0);
            if (result == 0 || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new GpuException("Julia preview shader compilation failed: "
                        + (result == 0 ? "no result" : shaderc_result_get_error_message(result)), false);
            }
            ByteBuffer code = CompiledShader.requireBytes(shaderc_result_get_bytes(result), "julia-preview.comp");
            LongBuffer handle = stack.callocLong(1);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default()
                    .pCode(code), null, handle), "preview shader module");
            module = handle.get(0);
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().layout(pipelineLayout);
            info.get(0).stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module).pName(stack.UTF8("main"));
            handle.put(0, 0);
            int status = vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, handle);
            pipeline = handle.get(0); // A partial creation still needs destruction on failure.
            check(status, "preview compute pipeline");
        } finally {
            if (module != 0) vkDestroyShaderModule(device, module, null);
            if (result != 0) shaderc_result_release(result);
            shaderc_compiler_release(compiler);
        }
    }

    synchronized GpuJuliaPreviewResult render(GpuJuliaPreviewRequest request) throws InterruptedException {
        if (closed) throw new IllegalStateException("Julia preview kernel is closed");
        if (deviceLost) throw new GpuException("Julia preview device lost", true);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Julia preview cancelled");
        long started = System.nanoTime();
        try {
            ensureBuffers(bytes((long) request.width() + request.height()), PALETTE_WORDS * Integer.BYTES,
                    bytes(request.pixelCount()));
            long allocated = System.nanoTime();
            request.writeAxes(buffers[0].mapped.asFloatBuffer());
            buffers[0].flush();
            if (residentPalette != request.palette()) {
                request.palette().writeLookup(buffers[1].ints());
                buffers[1].flush();
                residentPalette = request.palette();
            }
            long uploaded = System.nanoTime();
            dispatch(request);
            long dispatched = System.nanoTime();
            // Cancellation after submission still drains the fence first; the output is then discarded.
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Julia preview cancelled");
            buffers[2].invalidate();
            buffers[2].ints().get(request.colors());
            long done = System.nanoTime();
            long nativeBytes = 0;
            for (Buffer buffer : buffers) nativeBytes += buffer.allocationBytes;
            return new GpuJuliaPreviewResult(request.colors(), allocated - started, uploaded - allocated,
                    dispatched - uploaded, done - dispatched, done - started, nativeBytes);
        } catch (GpuException failure) {
            deviceLost |= failure.deviceLost();
            throw failure;
        }
    }

    private int bytes(long words) {
        long value = Math.multiplyExact(words, Integer.BYTES);
        if (words < 1 || value > maxStorage || value > Integer.MAX_VALUE) {
            throw new GpuException("Julia preview buffer exceeds storage/mapping limits", false);
        }
        return (int) value;
    }

    /**
     * Frees every buffer whose size changes before allocating any replacement, so the
     * budget only ever counts buffers the new frame keeps; otherwise a resize could count
     * the old, larger color buffer beside the new axes and refuse a frame that fits.
     */
    private void ensureBuffers(int... sizes) {
        for (int index = 0; index < DESCRIPTORS; index++) {
            if (buffers[index] != null && buffers[index].bytes != sizes[index]) {
                buffers[index].close();
                buffers[index] = null;
                if (index == 1) residentPalette = null;
            }
        }
        for (int index = 0; index < DESCRIPTORS; index++) {
            if (buffers[index] == null) {
                if (index == 1) residentPalette = null;
                buffers[index] = new Buffer(sizes[index]);
            }
        }
    }

    private void dispatch(GpuJuliaPreviewRequest request) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(DESCRIPTORS, stack);
            for (int i = 0; i < DESCRIPTORS; i++) {
                VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
                info.get(0).buffer(buffers[i].handle).offset(0).range(buffers[i].bytes);
                writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(info);
            }
            vkUpdateDescriptorSets(device, writes, null);
            check(vkResetCommandBuffer(command, 0), "reset preview command");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin preview command");
            vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                    stack.longs(descriptorSet), null);
            barrier(stack, VK_ACCESS_HOST_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            PaletteOffset o = request.offset();
            ByteBuffer constants = stack.calloc(PUSH_CONSTANT_BYTES);
            constants.asIntBuffer().put(new int[]{request.width(), request.height(), request.maxIterations(),
                    Float.floatToRawIntBits(request.colorScale()), Float.floatToRawIntBits(request.cReal()),
                    Float.floatToRawIntBits(request.cImaginary()), o.whole(), o.ascendingBias(), o.ascendingLast(),
                    o.descendingBias(), o.descendingLast()});
            vkCmdPushConstants(command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, constants);
            int groupsX = (request.width() + LOCAL_SIZE - 1) / LOCAL_SIZE;
            int groupsY = (request.height() + LOCAL_SIZE - 1) / LOCAL_SIZE;
            if (groupsX > maxGroupsX || groupsY > maxGroupsY) {
                throw new GpuException("Julia preview dispatch exceeds group-count limits", false);
            }
            vkCmdDispatch(command, groupsX, groupsY, 1);
            barrier(stack, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT);
            check(vkEndCommandBuffer(command), "end preview command");
            check(vkResetFences(device, fence), "reset preview fence");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(command.address()));
            check(vkQueueSubmit(queue, submit, fence), "submit preview command");
            // Never abandon a live submission, even on cancellation: drain before reuse/destruction.
            int status;
            do {
                status = vkWaitForFences(device, fence, true, 10_000_000L);
            } while (status == VK_TIMEOUT);
            check(status, "wait preview fence");
        }
    }

    private void barrier(MemoryStack stack, int sourceAccess, int destinationAccess,
                         int sourceStage, int destinationStage) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default().srcAccessMask(sourceAccess).dstAccessMask(destinationAccess);
        vkCmdPipelineBarrier(command, sourceStage, destinationStage, 0, barrier, null, null);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        // render drains every submitted fence, including cancellation. No work can outlive this lock.
        for (int i = 0; i < buffers.length; i++) {
            if (buffers[i] != null) { buffers[i].close(); buffers[i] = null; }
        }
        if (fence != 0) { vkDestroyFence(device, fence, null); fence = 0; }
        if (commandPool != 0) { vkDestroyCommandPool(device, commandPool, null); commandPool = 0; }
        if (pipeline != 0) { vkDestroyPipeline(device, pipeline, null); pipeline = 0; }
        if (pipelineLayout != 0) { vkDestroyPipelineLayout(device, pipelineLayout, null); pipelineLayout = 0; }
        if (descriptorPool != 0) { vkDestroyDescriptorPool(device, descriptorPool, null); descriptorPool = 0; }
        if (descriptorLayout != 0) { vkDestroyDescriptorSetLayout(device, descriptorLayout, null); descriptorLayout = 0; }
        residentPalette = null;
    }

    private final class Buffer implements AutoCloseable {
        private final int bytes;
        private long handle, memory, allocationBytes;
        private ByteBuffer mapped;
        private boolean coherent;

        private Buffer(int bytes) {
            this.bytes = bytes;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer out = stack.callocLong(1);
                check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
                        .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE),
                        null, out), "create preview buffer");
                handle = out.get(0);
                VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
                vkGetBufferMemoryRequirements(device, handle, requirements);
                allocationBytes = requirements.size();
                long total = allocationBytes;
                for (Buffer buffer : buffers) if (buffer != null) total += buffer.allocationBytes;
                if (budget <= 0 || total > budget) {
                    throw new GpuException("Julia preview allocation budget exceeded", false);
                }
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
                if (selected < 0) throw new GpuException("No host-visible Julia preview memory", false);
                coherent = (props.memoryTypes(selected).propertyFlags() & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
                check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                        .allocationSize(allocationBytes).memoryTypeIndex(selected), null, out),
                        "allocate preview memory");
                memory = out.get(0);
                check(vkBindBufferMemory(device, handle, memory, 0), "bind preview memory");
                PointerBuffer pointer = stack.mallocPointer(1);
                check(vkMapMemory(device, memory, 0, VK_WHOLE_SIZE, 0, pointer), "map preview memory");
                mapped = MemoryUtil.memByteBuffer(pointer.get(0), bytes).order(ByteOrder.nativeOrder());
            } catch (RuntimeException | LinkageError failure) {
                close();
                throw failure;
            }
        }

        private IntBuffer ints() { return mapped.asIntBuffer(); }
        private void flush() { if (!coherent) synchronizeMemory(true); }
        private void invalidate() { if (!coherent) synchronizeMemory(false); }

        private void synchronizeMemory(boolean flush) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkMappedMemoryRange.Buffer range = VkMappedMemoryRange.calloc(1, stack);
                range.get(0).sType$Default().memory(memory).offset(0).size(VK_WHOLE_SIZE);
                check(flush ? vkFlushMappedMemoryRanges(device, range) : vkInvalidateMappedMemoryRanges(device, range),
                        flush ? "flush preview memory" : "invalidate preview memory");
            }
        }

        @Override public void close() {
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
