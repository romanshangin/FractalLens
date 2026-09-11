package com.shangin.fractal.ui;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

/** Test-only Objective-C inspection; no production testing selectors or input synthesis. */
final class AppKitMenuProbe {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup OBJC = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
    private static final MethodHandle SELECTOR = LINKER.downcallHandle(OBJC.find("sel_registerName").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    static MemorySegment session(FractalContextMenu owner) throws Exception {
        var field = FractalContextMenu.class.getDeclaredField("nativeMenu");
        field.setAccessible(true);
        Object session = field.get(owner);
        if (session == null) throw new IllegalStateException("No native session");
        var handle = MacContextMenu.class.getDeclaredField("handle");
        handle.setAccessible(true);
        return MemorySegment.ofAddress(handle.getLong(session));
    }

    static MemorySegment menu(FractalContextMenu owner) throws Exception {
        return (MemorySegment) message(session(owner), "menu", ADDRESS);
    }

    static Object message(MemorySegment object, String selector, MemoryLayout result,
                          MemoryLayout[] layouts, Object... arguments) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = (MemorySegment) SELECTOR.invokeExact(arena.allocateFrom(selector));
            var types = new MemoryLayout[layouts.length + 2];
            types[0] = ADDRESS; types[1] = ADDRESS;
            System.arraycopy(layouts, 0, types, 2, layouts.length);
            var values = new Object[arguments.length + 2];
            values[0] = object; values[1] = name;
            System.arraycopy(arguments, 0, values, 2, arguments.length);
            var descriptor = result == null ? FunctionDescriptor.ofVoid(types) : FunctionDescriptor.of(result, types);
            return LINKER.downcallHandle(OBJC.find("objc_msgSend").orElseThrow(), descriptor).invokeWithArguments(values);
        } catch (Throwable error) { throw new AssertionError("AppKit probe: " + selector, error); }
    }

    static Object message(MemorySegment object, String selector, MemoryLayout result) {
        return message(object, selector, result, new MemoryLayout[0]);
    }

    static void select(FractalContextMenu owner) throws Exception {
        var menu = menu(owner);
        message(menu, "performActionForItemAtIndex:", null, new MemoryLayout[]{JAVA_LONG}, 0L);
    }

    static void cancel(FractalContextMenu owner) throws Exception {
        message(menu(owner), "cancelTrackingWithoutAnimation", null);
    }

    static boolean tracking(FractalContextMenu owner) throws Exception {
        return (byte) message(session(owner), "tracking", JAVA_BYTE) != 0;
    }
}
