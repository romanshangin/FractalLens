package com.shangin.fractal.ui;

import javafx.stage.Window;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

/** A temporary AppKit overlay. Calls run only on the macOS JavaFX/AppKit thread. */
final class MacLoadingMaterial implements AutoCloseable {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup OBJC = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
    private static final MethodHandle CLASS = function("objc_getClass", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle SELECTOR = function("sel_registerName", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GET = message(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle VOID = message(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle OBJECT = message(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle INTEGER = message(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG));
    private static final MemoryLayout RECT = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);
    private static final MethodHandle FRAME = message(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, RECT));
    private MemorySegment view = MemorySegment.NULL;

    static MacLoadingMaterial show(Window window) throws ReflectiveOperationException {
        if (!javafx.application.Platform.isFxApplicationThread()) throw new IllegalStateException("FX thread required");
        Object peer = Class.forName("com.sun.javafx.stage.WindowHelper")
                .getMethod("getPeer", Window.class).invoke(null, window);
        long handle = (long) Class.forName("com.sun.javafx.tk.TKStage")
                .getMethod("getRawHandle").invoke(peer);
        if (handle == 0) throw new IllegalStateException("Window has no native peer");
        var result = new MacLoadingMaterial();
        try {
            MemorySegment content = get(MemorySegment.ofAddress(handle), "contentView");
            result.view = get(get(type("NSVisualEffectView"), "alloc"), "init");
            frame(result.view, 0, 0, window.getScene().getWidth(), window.getScene().getHeight());
            integer(result.view, "setAutoresizingMask:", 18); // width + height
            integer(result.view, "setMaterial:", 7); // NSVisualEffectMaterialSidebar
            integer(result.view, "setBlendingMode:", 0); // behindWindow
            integer(result.view, "setState:", 0); // followsWindowActiveState
            MemorySegment icon = get(get(type("NSImageView"), "alloc"), "init");
            try {
                frame(icon, (window.getScene().getWidth() - 96) / 2,
                        (window.getScene().getHeight() - 96) / 2, 96, 96);
                integer(icon, "setAutoresizingMask:", 45); // flexible margins keep the icon centered
                object(icon, "setImage:", get(get(type("NSApplication"), "sharedApplication"), "applicationIconImage"));
                object(result.view, "addSubview:", icon);
            } finally {
                call(VOID, icon, selector("release"));
            }
            object(content, "addSubview:", result.view);
            return result;
        } catch (RuntimeException | Error error) {
            result.close();
            throw error;
        }
    }

    @Override public void close() {
        if (view.address() != 0) {
            call(VOID, view, selector("removeFromSuperview"));
            call(VOID, view, selector("release"));
            view = MemorySegment.NULL;
        }
    }

    private static MethodHandle function(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(OBJC.find(name).orElseThrow(), descriptor);
    }
    private static MethodHandle message(FunctionDescriptor descriptor) { return function("objc_msgSend", descriptor); }
    private static Object call(MethodHandle method, Object... args) {
        try { return method.invokeWithArguments(args); }
        catch (Throwable error) { throw new IllegalStateException("AppKit loading material failed", error); }
    }
    private static MemorySegment named(MethodHandle method, String name) {
        try (Arena arena = Arena.ofConfined()) { return (MemorySegment) call(method, arena.allocateFrom(name)); }
    }
    private static MemorySegment type(String name) { return named(CLASS, name); }
    private static MemorySegment selector(String name) { return named(SELECTOR, name); }
    private static MemorySegment get(MemorySegment object, String selector) {
        return (MemorySegment) call(GET, object, selector(selector));
    }
    private static void object(MemorySegment object, String selector, MemorySegment value) {
        call(OBJECT, object, selector(selector), value);
    }
    private static void integer(MemorySegment object, String selector, long value) {
        call(INTEGER, object, selector(selector), value);
    }
    private static void frame(MemorySegment object, double x, double y, double width, double height) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rect = arena.allocate(RECT);
            rect.set(JAVA_DOUBLE, 0, x); rect.set(JAVA_DOUBLE, 8, y);
            rect.set(JAVA_DOUBLE, 16, width); rect.set(JAVA_DOUBLE, 24, height);
            call(FRAME, object, selector("setFrame:"), rect);
        }
    }
}
