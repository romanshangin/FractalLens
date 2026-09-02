package com.shangin.fractal.gpu;

import org.lwjgl.system.Configuration;
import org.lwjgl.vulkan.VK;

/** LWJGL's loader is process-wide; one runtime must not unload another's code. */
final class VulkanLibraryLease implements AutoCloseable {
    private static int users;
    private static boolean owned;
    private boolean closed;

    private VulkanLibraryLease() {
    }

    static synchronized VulkanLibraryLease acquire() {
        // Configure before VK/MemoryUtil initialization, including IDE and headless launches.
        // Java 25+ is required by the project; FFM avoids deprecated sun.misc.Unsafe calls.
        if (Configuration.MEMORY_BACKEND.get() == null) {
            Configuration.MEMORY_BACKEND.set("ffm");
        }
        Configuration.VULKAN_EXPLICIT_INIT.set(true);
        if (users == 0) {
            try {
                VK.getFunctionProvider();
                owned = false; // Respect a loader initialized by another component.
            } catch (IllegalStateException notLoaded) {
                try {
                    VK.create();
                    owned = true;
                } catch (RuntimeException | LinkageError failure) {
                    VK.destroy();
                    throw failure;
                }
            }
        }
        users++;
        return new VulkanLibraryLease();
    }

    @Override
    public void close() {
        synchronized (VulkanLibraryLease.class) {
            if (!closed) {
                closed = true;
                if (--users == 0 && owned) {
                    owned = false;
                    VK.destroy();
                }
            }
        }
    }
}
