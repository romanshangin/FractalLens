package com.shangin.fractal.gpu;

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

/** Bounded synchronous compute kernel, owned and serialized by the GPU runtime. */
final class VulkanMandelbrotKernel implements AutoCloseable {
    static final int CAPACITY = 192 * 192;
    static final int INPUT_WORDS = 8;
    static final int OUTPUT_WORDS = 12;
    private static final int LOCAL_SIZE = 64;
    private final VkDevice device;
    private final VkPhysicalDevice physical;
    private final VkQueue queue;
    private final int family;
    private final Buffer[] buffers = new Buffer[2];
    private final long budget = 4L * 1024 * 1024;
    private long maxStorage;
    private int maxGroupsX, maxGroupsY;
    private long descriptorLayout, descriptorPool, descriptorSet, pipelineLayout, pipeline, commandPool, fence;
    private VkCommandBuffer command;
    private boolean closed;
    private boolean deviceLost;
    private String shaderHash;
    boolean interruptAfterSubmit;
    private final boolean profiling = Boolean.getBoolean("fractal.gpu.mandelbrot.profile");
    private long queryPool;
    private int timestampBits;
    private float timestampPeriod;
    private long kernelNanos;
    private MandelbrotTiming lastTiming;

    MandelbrotTiming lastTiming() { return lastTiming; }

    private VulkanMandelbrotKernel(VkDevice device, VkPhysicalDevice physical, VkQueue queue, int family) {
        this.device = device;
        this.physical = physical;
        this.queue = queue;
        this.family = family;
    }

    static VulkanMandelbrotKernel open(VkDevice device, VkPhysicalDevice physical, VkQueue queue, int family) {
        VulkanMandelbrotKernel kernel = new VulkanMandelbrotKernel(device, physical, queue, family);
        try {
            kernel.initialize();
            kernel.buffers[0] = kernel.new Buffer(CAPACITY * INPUT_WORDS * 4);
            kernel.buffers[1] = kernel.new Buffer(CAPACITY * OUTPUT_WORDS * 4);
            return kernel;
        } catch (RuntimeException | LinkageError failure) {
            kernel.close();
            throw failure;
        }
    }

    String shaderHash() { return shaderHash; }

    private void initialize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, properties);
            VkPhysicalDeviceLimits limits = properties.limits();
            maxStorage = Integer.toUnsignedLong(limits.maxStorageBufferRange());
            maxGroupsX = limits.maxComputeWorkGroupCount(0);
            maxGroupsY = limits.maxComputeWorkGroupCount(1);
            if ((long) CAPACITY * OUTPUT_WORDS * 4 > maxStorage) {
                throw new GpuException("Mandelbrot buffers exceed storage limits", false);
            }
            if (limits.maxComputeWorkGroupInvocations() < LOCAL_SIZE
                    || limits.maxComputeWorkGroupSize(0) < LOCAL_SIZE
                    || limits.maxPerStageDescriptorStorageBuffers() < 2
                    || limits.maxDescriptorSetStorageBuffers() < 2 || limits.maxPushConstantsSize() < 16) {
                throw new GpuException("Mandelbrot kernel exceeds compute limits", false);
            }
            LongBuffer handle = stack.callocLong(1);
            if (profiling) {
                IntBuffer count = stack.callocInt(1);
                vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
                var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
                timestampBits = families.get(family).timestampValidBits();
                timestampPeriod = limits.timestampPeriod();
                if (timestampBits > 0 && timestampPeriod > 0) {
                    check(vkCreateQueryPool(device, VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                            .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2), null, handle), "timestamp query pool");
                    queryPool = handle.get(0);
                }
            }
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            for (int i = 0; i < 2; i++) {
                bindings.get(i).binding(i).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings), null, handle), "descriptor layout");
            descriptorLayout = handle.get(0);
            VkPushConstantRange.Buffer ranges = VkPushConstantRange.calloc(1, stack);
            ranges.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(ranges), null, handle),
                    "pipeline layout");
            pipelineLayout = handle.get(0);
            createPipeline(stack);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
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
            try (var input = VulkanMandelbrotKernel.class.getResourceAsStream("mandelbrot-precision.comp")) {
                if (input == null) throw new GpuException("Missing mandelbrot-precision.comp resource", false);
                source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new GpuException("Cannot read Mandelbrot shader: " + failure.getMessage(), false);
            }
            result = shaderc_compile_into_spv(compiler, source, shaderc_compute_shader,
                    "mandelbrot-precision.comp", "main", 0);
            if (result == 0 || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new GpuException("Mandelbrot shader compilation failed: "
                        + (result == 0 ? "no result" : shaderc_result_get_error_message(result)), false);
            }
            ByteBuffer code = shaderc_result_get_bytes(result);
            shaderHash = verifySpirv(code);
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

    synchronized void calculate(float[] input, int count, int limit, int[] output) throws InterruptedException {
        if (closed) throw new IllegalStateException("Mandelbrot is closed");
        if (deviceLost) throw new GpuException("Mandelbrot device lost", true);
        if (count < 1 || count > CAPACITY || limit < 1 || limit > 10000
                || input.length < count * INPUT_WORDS || output.length < count * OUTPUT_WORDS) {
            throw new IllegalArgumentException("Mandelbrot batch exceeds fixed bounds");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Mandelbrot cancelled before submit");
        lastTiming = null;
        try {
            long started = profiling ? System.nanoTime() : 0;
            buffers[0].mapped.asFloatBuffer().put(input, 0, count * INPUT_WORDS);
            buffers[0].flush();
            long uploaded = profiling ? System.nanoTime() : 0;
            dispatch(count, limit);
            long dispatched = profiling ? System.nanoTime() : 0;
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Mandelbrot cancelled after fence");
            buffers[1].invalidate();
            buffers[1].ints().get(output, 0, count * OUTPUT_WORDS);
            if (profiling) lastTiming = new MandelbrotTiming(uploaded - started, dispatched - uploaded,
                    System.nanoTime() - dispatched, kernelNanos,
                    buffers[0].allocationBytes + buffers[1].allocationBytes);
        } catch (GpuException failure) {
            deviceLost |= failure.deviceLost();
            throw failure;
        }
    }

    private void dispatch(int count, int limit) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            for (int i = 0; i < 2; i++) {
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
            ByteBuffer constants = stack.malloc(16);
            constants.asIntBuffer().put(new int[]{count, limit, 0, 0});
            vkCmdPushConstants(command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, constants);
            if (queryPool != 0) {
                vkCmdResetQueryPool(command, queryPool, 0, 2);
                vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, 0);
            }
            dispatchCount(count);
            if (queryPool != 0) vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 1);
            barrier(stack, VK_ACCESS_HOST_READ_BIT, VK_PIPELINE_STAGE_HOST_BIT);
            check(vkEndCommandBuffer(command), "end command");
            check(vkResetFences(device, fence), "reset fence");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(command.address()));
            check(vkQueueSubmit(queue, submit, fence), "submit Mandelbrot");
            if (interruptAfterSubmit) Thread.currentThread().interrupt();
            // Never abandon a live submission, even on cancellation: drain before reuse/destruction.
            int status;
            do {
                status = vkWaitForFences(device, fence, true, 10_000_000L);
            } while (status == VK_TIMEOUT);
            check(status, "wait Mandelbrot fence");
            kernelNanos = -1;
            if (queryPool != 0) {
                LongBuffer timestamps = stack.callocLong(2);
                check(vkGetQueryPoolResults(device, queryPool, 0, 2, timestamps, Long.BYTES,
                        VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT), "read timestamps");
                long ticks = timestamps.get(1) - timestamps.get(0);
                if (timestampBits < 64) ticks &= (1L << timestampBits) - 1;
                kernelNanos = Math.round(ticks * (double) timestampPeriod);
            }
        }
    }

    private void dispatchCount(int count) {
        long groups = ((long) count + LOCAL_SIZE - 1) / LOCAL_SIZE;
        int x = (int) Math.min(groups, maxGroupsX);
        long y = (groups + x - 1) / x;
        if (y > maxGroupsY) throw new GpuException("Mandelbrot dispatch exceeds group-count limits", false);
        vkCmdDispatch(command, x, (int) y, 1);
    }

    private void barrier(MemoryStack stack, int destinationAccess, int destinationStage) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(destinationAccess);
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, destinationStage, 0,
                barrier, null, null);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        // calculate drains every submitted fence, including cancellation. No work can outlive this lock.
        for (int i = 0; i < buffers.length; i++) {
            if (buffers[i] != null) { buffers[i].close(); buffers[i] = null; }
        }
        if (queryPool != 0) { vkDestroyQueryPool(device, queryPool, null); queryPool = 0; }
        if (fence != 0) { vkDestroyFence(device, fence, null); fence = 0; }
        if (commandPool != 0) { vkDestroyCommandPool(device, commandPool, null); commandPool = 0; }
        if (pipeline != 0) { vkDestroyPipeline(device, pipeline, null); pipeline = 0; }
        if (pipelineLayout != 0) { vkDestroyPipelineLayout(device, pipelineLayout, null); pipelineLayout = 0; }
        if (descriptorPool != 0) { vkDestroyDescriptorPool(device, descriptorPool, null); descriptorPool = 0; }
        if (descriptorLayout != 0) { vkDestroyDescriptorSetLayout(device, descriptorLayout, null); descriptorLayout = 0; }
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
                if (residentBytes > budget) throw new GpuException("Mandelbrot allocation budget exceeded", false);
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
                if (selected < 0) throw new GpuException("No host-visible Mandelbrot memory", false);
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

    private static String verifySpirv(ByteBuffer code) {
        IntBuffer words = code.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        java.util.Set<Integer> precise = new java.util.HashSet<>();
        java.util.Set<Integer> arithmetic = new java.util.HashSet<>();
        for (int i = 5; i < words.limit();) {
            int instruction = words.get(i), length = instruction >>> 16, opcode = instruction & 65535;
            if (length < 1 || i + length > words.limit()) throw new GpuException("Malformed SPIR-V", false);
            if (opcode == 71 && words.get(i + 2) == 0) throw new GpuException("RelaxedPrecision is forbidden", false);
            if (opcode == 71 && words.get(i + 2) == 42) precise.add(words.get(i + 1));
            if (opcode == 129 || opcode == 131 || opcode == 133) arithmetic.add(words.get(i + 2));
            i += length;
        }
        if (arithmetic.isEmpty() || !precise.containsAll(arithmetic)) {
            throw new GpuException("Every float add/subtract/multiply must carry NoContraction", false);
        }
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(code.duplicate());
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new GpuException(operation + " failed with VkResult " + result,
                result == VK_ERROR_DEVICE_LOST);
    }
}
