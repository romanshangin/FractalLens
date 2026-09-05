package com.shangin.fractal.gpu;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.KHRPortabilityEnumeration.*;
import static org.lwjgl.vulkan.KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;

/** Headless compute session. No surfaces, swapchains, or JavaFX native handles. */
final class MacosMoltenVkSession implements GpuSession {
    private VulkanLibraryLease library;
    private VkInstance instance;
    private VkDevice device;
    private VkQueue queue;
    private VkPhysicalDevice physical;
    private VulkanPaletteKernel paletteKernel;
    private VulkanMandelbrotKernel mandelbrotKernel;
    private VulkanResidentMandelbrotKernel residentMandelbrotKernel;
    private List<GpuDevice> devices = List.of();
    private GpuDevice selectedDevice;

    private MacosMoltenVkSession() {
    }

    static GpuSession open() {
        MacosMoltenVkSession session = new MacosMoltenVkSession();
        try {
            session.initialize();
            return session;
        } catch (RuntimeException | LinkageError failure) {
            try {
                session.close(failure instanceof GpuException gpu && gpu.deviceLost());
            } catch (RuntimeException | LinkageError cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void initialize() {
        library = VulkanLibraryLease.acquire();
        if (VK.getInstanceVersionSupported() < VK_API_VERSION_1_1) {
            throw new GpuException("Vulkan 1.1 or newer is required", false);
        }
        Set<String> instanceExtensions = extensions(null);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationName(stack.UTF8("FractalUI"))
                    .apiVersion(VK_API_VERSION_1_1);
            VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default().pApplicationInfo(app);
            // The loader hides MoltenVK devices unless portability is opted in.
            if (instanceExtensions.contains(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)) {
                info.flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR)
                        .ppEnabledExtensionNames(stack.pointers(
                                stack.UTF8(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)));
            }
            PointerBuffer handle = stack.mallocPointer(1);
            check(vkCreateInstance(info, null, handle), "vkCreateInstance");
            instance = new VkInstance(handle.get(0), info);
        }

        List<Candidate> candidates = physicalDevices();
        devices = candidates.stream().map(Candidate::description).toList();
        Candidate selected = candidates.stream()
                .filter(candidate -> candidate.description().supports(GpuNumericCapability.FLOAT32))
                .filter(candidate -> candidate.apiVersion() >= VK_API_VERSION_1_1)
                .filter(candidate -> candidate.type() != VK_PHYSICAL_DEVICE_TYPE_CPU)
                .max(Comparator.comparingInt(candidate -> switch (candidate.type()) {
                    case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 3;
                    case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 2;
                    case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 1;
                    default -> 0;
                }))
                .orElseThrow(() -> new GpuException("No Vulkan 1.1 GPU with a compute queue found", false));
        selectedDevice = selected.description();
        createDevice(selected);
    }

    private void createDevice(Candidate selected) {
        physical = selected.physical();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack);
            queues.get(0).sType$Default()
                    .queueFamilyIndex(selectedDevice.computeQueueFamily())
                    .pQueuePriorities(stack.floats(1.0f));
            VkPhysicalDeviceFeatures enabled = VkPhysicalDeviceFeatures.calloc(stack)
                    .shaderFloat64(selectedDevice.supports(GpuNumericCapability.FLOAT64));
            VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack)
                    .sType$Default().pQueueCreateInfos(queues).pEnabledFeatures(enabled);
            if (selected.extensions().contains(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME)) {
                info.ppEnabledExtensionNames(stack.pointers(
                        stack.UTF8(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME)));
            }
            PointerBuffer handle = stack.mallocPointer(1);
            check(vkCreateDevice(selected.physical(), info, null, handle), "vkCreateDevice");
            device = new VkDevice(handle.get(0), selected.physical(), info);
            vkGetDeviceQueue(device, selectedDevice.computeQueueFamily(), 0, handle);
            if (handle.get(0) == 0) {
                throw new GpuException("No compute queue returned by vkGetDeviceQueue", false);
            }
            queue = new VkQueue(handle.get(0), device);
        }
    }

    private List<Candidate> physicalDevices() {
        for (int attempt = 0; attempt < 3; attempt++) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer count = stack.ints(0);
                check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices(count)");
                if (count.get(0) == 0) {
                    return List.of();
                }
                PointerBuffer handles = stack.mallocPointer(count.get(0));
                int result = vkEnumeratePhysicalDevices(instance, count, handles);
                if (result == VK_INCOMPLETE) {
                    continue;
                }
                check(result, "vkEnumeratePhysicalDevices");
                List<Candidate> candidates = new ArrayList<>();
                for (int i = 0; i < count.get(0); i++) {
                    VkPhysicalDevice physical = new VkPhysicalDevice(handles.get(i), instance);
                    VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
                    VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
                    vkGetPhysicalDeviceProperties(physical, properties);
                    vkGetPhysicalDeviceFeatures(physical, features);
                    EnumSet<GpuNumericCapability> numeric = EnumSet.of(GpuNumericCapability.FLOAT32);
                    if (features.shaderFloat64()) {
                        numeric.add(GpuNumericCapability.FLOAT64);
                    }
                    GpuDevice description = new GpuDevice(
                            properties.deviceNameString(), version(properties.apiVersion()),
                            properties.vendorID(), properties.deviceID(), computeQueueFamily(physical),
                            properties.limits().maxComputeWorkGroupInvocations(),
                            Integer.toUnsignedLong(properties.limits().maxStorageBufferRange()), numeric);
                    candidates.add(new Candidate(physical, description, properties.deviceType(),
                            properties.apiVersion(), extensions(physical)));
                }
                return candidates;
            }
        }
        throw new GpuException("Physical-device enumeration kept changing", false);
    }

    private static int computeQueueFamily(VkPhysicalDevice physical) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
            int selected = -1;
            for (int i = 0; i < count.get(0); i++) {
                VkQueueFamilyProperties family = families.get(i);
                if (family.queueCount() > 0 && (family.queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                    selected = i;
                    if ((family.queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) {
                        break;
                    }
                }
            }
            return selected;
        }
    }

    private static Set<String> extensions(VkPhysicalDevice physical) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer count = stack.ints(0);
                int result = physical == null
                        ? vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, null)
                        : vkEnumerateDeviceExtensionProperties(physical, (ByteBuffer) null, count, null);
                check(result, "enumerate extensions(count)");
                VkExtensionProperties.Buffer properties = VkExtensionProperties.calloc(count.get(0), stack);
                result = physical == null
                        ? vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, properties)
                        : vkEnumerateDeviceExtensionProperties(physical, (ByteBuffer) null, count, properties);
                if (result == VK_INCOMPLETE) {
                    continue;
                }
                check(result, "enumerate extensions");
                Set<String> names = new HashSet<>();
                for (int i = 0; i < count.get(0); i++) {
                    names.add(properties.get(i).extensionNameString());
                }
                return names;
            }
        }
        throw new GpuException("Extension enumeration kept changing", false);
    }

    private static String version(int version) {
        return VK_VERSION_MAJOR(version) + "." + VK_VERSION_MINOR(version) + "." + VK_VERSION_PATCH(version);
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new GpuException(operation + " failed with VkResult " + result, result == VK_ERROR_DEVICE_LOST);
        }
    }

    @Override
    public List<GpuDevice> devices() {
        return devices;
    }

    @Override
    public GpuDevice selectedDevice() {
        return selectedDevice;
    }

    @Override
    public void checkHealth() {
        check(vkQueueWaitIdle(queue), "vkQueueWaitIdle");
    }

    @Override
    public PaletteRecolorTiming recolorPalette(PaletteRecolorRequest request) throws InterruptedException {
        long started = System.nanoTime();
        try {
            if (paletteKernel == null) {
                paletteKernel = VulkanPaletteKernel.open(device, physical, queue, selectedDevice.computeQueueFamily());
            }
            long ready = System.nanoTime();
            PaletteRecolorTiming timing = paletteKernel.recolor(request);
            return timing.withPreparation(ready - started, System.nanoTime() - started);
        } catch (LinkageError failure) {
            throw new GpuException("Palette native dependency unavailable: " + failure.getMessage(), false);
        }
    }

    @Override
    public void close(boolean deviceLost) {
        try {
            if (device != null && !deviceLost) {
                check(vkDeviceWaitIdle(device), "vkDeviceWaitIdle");
            }
        } finally {
            try {
                if (device != null) {
                    if (mandelbrotKernel != null) {
                        mandelbrotKernel.close();
                        mandelbrotKernel = null;
                    }
                    if (residentMandelbrotKernel != null) {
                        residentMandelbrotKernel.close();
                        residentMandelbrotKernel = null;
                    }
                    if (paletteKernel != null) {
                        paletteKernel.close();
                        paletteKernel = null;
                    }
                    vkDestroyDevice(device, null);
                    device = null;
                    queue = null;
                }
            } finally {
                try {
                    if (instance != null) {
                        vkDestroyInstance(instance, null);
                        instance = null;
                    }
                } finally {
                    if (library != null) {
                        library.close();
                        library = null;
                    }
                }
            }
        }
    }

    private record Candidate(VkPhysicalDevice physical, GpuDevice description, int type,
                             int apiVersion, Set<String> extensions) {
    }

    @Override
    public void calculateMandelbrot(MandelbrotBatch batch) throws InterruptedException {
        try {
            if (mandelbrotKernel == null) {
                mandelbrotKernel = VulkanMandelbrotKernel.open(device, physical, queue, selectedDevice.computeQueueFamily());
            }
            mandelbrotKernel.calculate(batch.input, batch.count, batch.maxIterations, batch.output);
            batch.timing = mandelbrotKernel.lastTiming();
        } catch (LinkageError failure) {
            throw new GpuException("Mandelbrot native dependency unavailable: " + failure.getMessage(), false);
        }
    }

    @Override
    public GpuResidentMandelbrotResult renderResidentMandelbrot(
            GpuResidentMandelbrotRequest request) throws InterruptedException {
        try {
            if (residentMandelbrotKernel == null) {
                residentMandelbrotKernel = VulkanResidentMandelbrotKernel.open(
                        device, physical, queue, selectedDevice.computeQueueFamily());
            }
            return residentMandelbrotKernel.render(request);
        } catch (LinkageError failure) {
            throw new GpuException("Resident Mandelbrot dependency unavailable: " + failure.getMessage(), false);
        }
    }
}
