package com.shangin.fractal.ui;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import static java.lang.foreign.ValueLayout.*;

/** Test-only Objective-C inspection; no production testing selectors or input synthesis. */
final class AppKitMenuProbe {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup OBJC = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
    private static final MethodHandle SELECTOR = LINKER.downcallHandle(OBJC.find("sel_registerName").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    static MemorySegment mainMenu() throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            var getClass = LINKER.downcallHandle(OBJC.find("objc_getClass").orElseThrow(),
                    FunctionDescriptor.of(ADDRESS, ADDRESS));
            var applicationType = (MemorySegment) getClass.invokeExact(arena.allocateFrom("NSApplication"));
            var application = (MemorySegment) message(applicationType, "sharedApplication", ADDRESS);
            return (MemorySegment) message(application, "mainMenu", ADDRESS);
        }
    }

    static List<String> mainMenuTitles() throws Throwable {
        var menu = mainMenu();
        long count = (long) message(menu, "numberOfItems", JAVA_LONG);
        var titles = new ArrayList<String>();
        for (long index = 0; index < count; index++) {
            var item = (MemorySegment) message(menu, "itemAtIndex:", ADDRESS,
                    new MemoryLayout[]{JAVA_LONG}, index);
            var title = (MemorySegment) message(item, "title", ADDRESS);
            var bytes = (MemorySegment) message(title, "UTF8String", ADDRESS);
            titles.add(bytes.reinterpret(Long.MAX_VALUE).getString(0));
        }
        return titles;
    }

    static void updateSubmenu(String title) {
        try (Arena arena = Arena.ofConfined()) {
            var text = (MemorySegment) message(objcClass("NSString", arena), "stringWithUTF8String:", ADDRESS,
                    new MemoryLayout[]{ADDRESS}, arena.allocateFrom(title));
            var item = (MemorySegment) message(mainMenu(), "itemWithTitle:", ADDRESS,
                    new MemoryLayout[]{ADDRESS}, text);
            var submenu = (MemorySegment) message(item, "submenu", ADDRESS);
            message(submenu, "update", null);
        } catch (Throwable failure) { throw new AssertionError("Cannot update " + title, failure); }
    }

    static boolean enabled(String path) {
        try {
            MemorySegment menu = mainMenu();
            MemorySegment item = MemorySegment.NULL;
            for (String title : path.split("/")) {
                try (Arena arena = Arena.ofConfined()) {
                    var text = (MemorySegment) message(
                            objcClass("NSString", arena), "stringWithUTF8String:", ADDRESS,
                            new MemoryLayout[]{ADDRESS}, arena.allocateFrom(title));
                    item = (MemorySegment) message(menu, "itemWithTitle:", ADDRESS,
                            new MemoryLayout[]{ADDRESS}, text);
                }
                if (item.equals(MemorySegment.NULL)) throw new AssertionError("Missing native item: " + path);
                menu = (MemorySegment) message(item, "submenu", ADDRESS);
            }
            return (byte) message(item, "isEnabled", JAVA_BYTE) != 0;
        } catch (Throwable failure) { throw new AssertionError("Cannot inspect " + path, failure); }
    }

    private static MemorySegment objcClass(String name, Arena arena) throws Throwable {
        var getClass = LINKER.downcallHandle(OBJC.find("objc_getClass").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        return (MemorySegment) getClass.invokeExact(arena.allocateFrom(name));
    }

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
