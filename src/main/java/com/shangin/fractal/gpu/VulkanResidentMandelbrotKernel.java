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

/** Whole-frame experimental kernel. It is intentionally outside production backend selection. */
final class VulkanResidentMandelbrotKernel implements AutoCloseable {
    private static final int LOCAL_SIZE = 64;
    private static final int DESCRIPTORS = 6;
    private static final int SAMPLE_WORDS = 4;
    private static final int REJECTION_HEADER_WORDS = 8;
    private static final int PALETTE_WORDS = 65_536;
    private final VkDevice device;
    private final VkPhysicalDevice physical;
    private final VkQueue queue;
    private final int family;
    private final Buffer[] buffers = new Buffer[DESCRIPTORS];
    private final long budget = Long.getLong("fractal.gpu.resident.maxBytes", 384L * 1024 * 1024);
    private long maxStorage;
    private int maxGroupsX, maxGroupsY;
    private long descriptorLayout, descriptorPool, descriptorSet, pipelineLayout, pipeline, commandPool, fence;
    private VkCommandBuffer command;
    private boolean closed, deviceLost;
    private GradientPalette residentPalette;

    private VulkanResidentMandelbrotKernel(
            VkDevice device, VkPhysicalDevice physical, VkQueue queue, int family) {
        this.device = device;
        this.physical = physical;
        this.queue = queue;
        this.family = family;
    }

    static VulkanResidentMandelbrotKernel open(
            VkDevice device, VkPhysicalDevice physical, VkQueue queue, int family) {
        var kernel = new VulkanResidentMandelbrotKernel(device, physical, queue, family);
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
                    || limits.maxPerStageDescriptorStorageBuffers() < DESCRIPTORS
                    || limits.maxDescriptorSetStorageBuffers() < DESCRIPTORS
                    || limits.maxPushConstantsSize() < 48) {
                throw new GpuException("Resident spike exceeds compute limits", false);
            }
            LongBuffer handle = stack.callocLong(1);
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(DESCRIPTORS, stack);
            for (int i = 0; i < DESCRIPTORS; i++) {
                bindings.get(i).binding(i).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings), null, handle), "resident descriptor layout");
            descriptorLayout = handle.get(0);
            VkPushConstantRange.Buffer ranges = VkPushConstantRange.calloc(1, stack);
            ranges.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(48);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(ranges), null, handle),
                    "resident pipeline layout");
            pipelineLayout = handle.get(0);
            createPipeline(stack);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(DESCRIPTORS);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(poolSizes), null, handle), "resident descriptor pool");
            descriptorPool = handle.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout)), handle),
                    "resident descriptor set");
            descriptorSet = handle.get(0);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT),
                    null, handle), "resident command pool");
            commandPool = handle.get(0);
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1),
                    pointer), "resident command buffer");
            command = new VkCommandBuffer(pointer.get(0), device);
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle),
                    "resident fence");
            fence = handle.get(0);
        }
    }

    private void createPipeline(MemoryStack stack) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) throw new GpuException("Cannot initialize shaderc", false);
        long result = 0, module = 0;
        try {
            String source;
            try (var input = VulkanResidentMandelbrotKernel.class
                    .getResourceAsStream("mandelbrot-resident.comp")) {
                if (input == null) throw new GpuException("Missing mandelbrot-resident.comp resource", false);
                source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new GpuException("Cannot read resident shader: " + failure.getMessage(), false);
            }
            result = shaderc_compile_into_spv(compiler, source, shaderc_compute_shader,
                    "mandelbrot-resident.comp", "main", 0);
            if (result == 0 || shaderc_result_get_compilation_status(result)
                    != shaderc_compilation_status_success) {
                throw new GpuException("Resident shader compilation failed: "
                        + (result == 0 ? "no result" : shaderc_result_get_error_message(result)), false);
            }
            ByteBuffer code = shaderc_result_get_bytes(result);
            LongBuffer handle = stack.callocLong(1);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(code), null, handle), "resident shader module");
            module = handle.get(0);
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().layout(pipelineLayout);
            info.get(0).stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module).pName(stack.UTF8("main"));
            handle.put(0, 0);
            int status = vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, handle);
            pipeline = handle.get(0);
            check(status, "resident compute pipeline");
        } finally {
            if (module != 0) vkDestroyShaderModule(device, module, null);
            if (result != 0) shaderc_result_release(result);
            shaderc_compiler_release(compiler);
        }
    }

    synchronized GpuResidentMandelbrotResult render(GpuResidentMandelbrotRequest request)
            throws InterruptedException {
        if (closed) throw new IllegalStateException("Resident kernel is closed");
        if (deviceLost) throw new GpuException("Resident kernel device lost", true);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Resident render cancelled");
        int pixels = request.pixelCount();
        int axisBytes = bytes(3L * (request.width() + request.height()));
        int sampleBytes = bytes((long) pixels * SAMPLE_WORDS);
        int rejectionBytes = bytes((long) pixels + REJECTION_HEADER_WORDS);
        int colorBytes = bytes(pixels);
        long started = System.nanoTime();
        ensureBuffer(0, axisBytes);
        ensureBuffer(1, sampleBytes);
        ensureBuffer(2, rejectionBytes);
        ensureBuffer(3, Integer.BYTES);
        ensureBuffer(4, PALETTE_WORDS * Integer.BYTES);
        ensureBuffer(5, colorBytes);
        long allocated = System.nanoTime();

        FloatBuffer axes = buffers[0].mapped.asFloatBuffer();
        request.writeAxes(axes);
        IntBuffer rejectionHeader = buffers[2].ints();
        for (int i = 0; i < REJECTION_HEADER_WORDS; i++) rejectionHeader.put(i, 0);
        boolean uploadPalette = residentPalette != request.palette();
        if (uploadPalette) {
            request.palette().writeLookup(buffers[4].ints());
            residentPalette = request.palette();
        }
        long packed = System.nanoTime();
        buffers[0].flush();
        buffers[2].flush();
        if (uploadPalette) buffers[4].flush();
        long uploaded = System.nanoTime();

        try {
            dispatch(request, 0, pixels, 0);
            long calculated = System.nanoTime();
            buffers[2].invalidate();
            IntBuffer rejection = buffers[2].ints();
            int rejectedCount = rejection.get(0);
            if (rejectedCount < 0 || rejectedCount > pixels) {
                throw new GpuException("Resident rejection counter is invalid: " + rejectedCount, false);
            }
            int[] rejected = new int[rejectedCount];
            rejection.position(REJECTION_HEADER_WORDS).get(rejected);
            var rejectionStats = new GpuResidentRejectionStats(
                    rejection.get(1), rejection.get(2), rejection.get(3), rejection.get(4),
                    rejection.get(5), rejection.get(6), rejection.get(7));
            if (rejectionStats.total() != rejectedCount) {
                throw new GpuException("Resident rejection reasons do not match counter", false);
            }
            long rejectedAt = System.nanoTime();
            GpuResidentCorrections corrections = request.recover(rejected);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Resident recovery cancelled");
            long recovered = System.nanoTime();
            ensureBuffer(3, rejectedCount == 0 ? Integer.BYTES : bytes((long) rejectedCount
                    * GpuResidentMandelbrotRequest.CORRECTION_WORDS));
            if (rejectedCount > 0) {
                buffers[3].ints().put(corrections.words());
                buffers[3].flush();
            }
            long correctionsAt = System.nanoTime();
            dispatch(request, 1, rejectedCount, pixels);
            long colored = System.nanoTime();
            buffers[5].invalidate();
            buffers[5].ints().get(request.colors());
            long done = System.nanoTime();
            long nativeBytes = 0;
            for (Buffer buffer : buffers) nativeBytes += buffer.allocationBytes;
            long uploadedBytes = axisBytes + (long) REJECTION_HEADER_WORDS * Integer.BYTES
                    + (uploadPalette ? (long) PALETTE_WORDS * Integer.BYTES : 0)
                    + (long) rejectedCount * GpuResidentMandelbrotRequest.CORRECTION_WORDS * Integer.BYTES;
            long readbackBytes = (long) (rejectedCount + REJECTION_HEADER_WORDS) * Integer.BYTES
                    + colorBytes;
            var timing = new GpuResidentMandelbrotTiming(
                    allocated - started, packed - allocated, uploaded - packed,
                    calculated - uploaded, rejectedAt - calculated, recovered - rejectedAt,
                    correctionsAt - recovered, colored - correctionsAt, done - colored,
                    done - started, nativeBytes, uploadedBytes, readbackBytes);
            return new GpuResidentMandelbrotResult(
                    request.colors(), pixels - rejectedCount, rejectedCount, rejectionStats, timing);
        } catch (GpuException failure) {
            deviceLost |= failure.deviceLost();
            throw failure;
        }
    }

    private int bytes(long words) {
        long value = Math.multiplyExact(words, Integer.BYTES);
        if (words < 1 || value > maxStorage || value > Integer.MAX_VALUE) {
            throw new GpuException("Resident buffer exceeds storage/mapping limits", false);
        }
        return (int) value;
    }

    private void ensureBuffer(int index, int bytes) {
        if (buffers[index] != null && buffers[index].bytes == bytes) return;
        if (buffers[index] != null) {
            buffers[index].close();
            buffers[index] = null;
        }
        if (index == 4) residentPalette = null;
        buffers[index] = new Buffer(bytes);
    }

    private void dispatch(GpuResidentMandelbrotRequest request, int firstPass, int count, int pixels)
            throws InterruptedException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            updateDescriptors(stack);
            check(vkResetCommandBuffer(command, 0), "reset resident command");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin resident command");
            vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                    stack.longs(descriptorSet), null);
            barrier(stack, VK_ACCESS_HOST_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            if (firstPass == 0) {
                push(request, 0, 0, stack);
                dispatchCount(request.pixelCount());
                barrier(stack, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT);
            } else {
                if (count > 0) {
                    push(request, 1, count, stack);
                    dispatchCount(count);
                    barrier(stack, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                }
                push(request, 2, count, stack);
                dispatchCount(pixels);
                barrier(stack, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT);
            }
            check(vkEndCommandBuffer(command), "end resident command");
            check(vkResetFences(device, fence), "reset resident fence");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(command.address()));
            check(vkQueueSubmit(queue, submit, fence), "submit resident command");
            int status;
            do {
                status = vkWaitForFences(device, fence, true, 10_000_000L);
            } while (status == VK_TIMEOUT);
            check(status, "wait resident fence");
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Resident render cancelled");
        }
    }

    private void updateDescriptors(MemoryStack stack) {
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(DESCRIPTORS, stack);
        for (int i = 0; i < DESCRIPTORS; i++) {
            VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
            info.get(0).buffer(buffers[i].handle).offset(0).range(buffers[i].bytes);
            writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(info);
        }
        vkUpdateDescriptorSets(device, writes, null);
    }

    private void push(GpuResidentMandelbrotRequest request, int pass, int corrections, MemoryStack stack) {
        PaletteOffset o = request.offset();
        ByteBuffer constants = stack.malloc(48);
        constants.asIntBuffer().put(new int[]{request.pixelCount(), request.width(), request.height(),
                request.maxIterations(), pass, corrections, Float.floatToRawIntBits(request.colorScale()),
                o.whole(), o.ascendingBias(), o.ascendingLast(), o.descendingBias(), o.descendingLast()});
        vkCmdPushConstants(command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, constants);
    }

    private void dispatchCount(int count) {
        long groups = ((long) count + LOCAL_SIZE - 1) / LOCAL_SIZE;
        int x = (int) Math.min(groups, maxGroupsX);
        long y = (groups + x - 1) / x;
        if (y > maxGroupsY) throw new GpuException("Resident dispatch exceeds group-count limits", false);
        vkCmdDispatch(command, x, (int) y, 1);
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
                        null, out), "create resident buffer");
                handle = out.get(0);
                VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
                vkGetBufferMemoryRequirements(device, handle, requirements);
                allocationBytes = requirements.size();
                long total = allocationBytes;
                for (Buffer buffer : buffers) if (buffer != null) total += buffer.allocationBytes;
                if (budget <= 0 || total > budget) throw new GpuException("Resident allocation budget exceeded", false);
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
                if (selected < 0) throw new GpuException("No host-visible resident memory", false);
                coherent = (props.memoryTypes(selected).propertyFlags()
                        & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
                check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                        .allocationSize(allocationBytes).memoryTypeIndex(selected), null, out),
                        "allocate resident memory");
                memory = out.get(0);
                check(vkBindBufferMemory(device, handle, memory, 0), "bind resident memory");
                PointerBuffer pointer = stack.mallocPointer(1);
                check(vkMapMemory(device, memory, 0, VK_WHOLE_SIZE, 0, pointer), "map resident memory");
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
                        flush ? "flush resident memory" : "invalidate resident memory");
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
