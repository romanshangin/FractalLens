package com.shangin.fractal.ui;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.FunctionDescriptor;
import static java.lang.foreign.ValueLayout.*;

/** Test-only inspection of the native sheet and its real target/action handlers. */
final class AppKitIterationProbe {
    static MacIterationSheet sheet(MainMenuBar menu) throws Exception {
        var field = MainMenuBar.class.getDeclaredField("iterationSheet");
        field.setAccessible(true);
        return (MacIterationSheet) field.get(menu);
    }

    static MemorySegment session(MacIterationSheet sheet) throws Exception {
        var field = MacIterationSheet.class.getDeclaredField("handle");
        field.setAccessible(true);
        long handle = field.getLong(sheet);
        if (handle == 0) throw new AssertionError("Native iteration sheet must be open");
        return MemorySegment.ofAddress(handle);
    }

    static MemorySegment field(MacIterationSheet sheet, String name) throws Exception {
        return (MemorySegment) AppKitMenuProbe.message(session(sheet), name, ADDRESS);
    }

    static String text(MacIterationSheet sheet, String name) throws Exception {
        var string = (MemorySegment) AppKitMenuProbe.message(field(sheet, name), "stringValue", ADDRESS);
        var bytes = (MemorySegment) AppKitMenuProbe.message(string, "UTF8String", ADDRESS);
        return bytes.reinterpret(Long.MAX_VALUE).getString(0);
    }

    static void text(MacIterationSheet sheet, String name, String value) throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            var type = objcClass("NSString", arena);
            var replacement = (MemorySegment) AppKitMenuProbe.message(type, "stringWithUTF8String:", ADDRESS,
                    new java.lang.foreign.MemoryLayout[]{ADDRESS}, arena.allocateFrom(value));
            AppKitMenuProbe.message(field(sheet, name), "setStringValue:", null,
                    new java.lang.foreign.MemoryLayout[]{ADDRESS}, replacement);
        }
    }

    static MemorySegment objcClass(String name, Arena arena) {
        var objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
        var method = Linker.nativeLinker().downcallHandle(objc.find("objc_getClass").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try { return (MemorySegment) method.invokeExact(arena.allocateFrom(name)); }
        catch (Throwable failure) { throw new AssertionError(failure); }
    }

    static void action(MacIterationSheet sheet, String action) throws Exception {
        AppKitMenuProbe.message(session(sheet), action + ":", null,
                new java.lang.foreign.MemoryLayout[]{ADDRESS}, MemorySegment.NULL);
    }

    static boolean showing(MacIterationSheet sheet) throws Exception {
        return (byte) AppKitMenuProbe.message(field(sheet, "panel"), "isVisible", JAVA_BYTE) != 0;
    }

    static void appearance(MacIterationSheet sheet, String name) throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            var appKit = SymbolLookup.libraryLookup("/System/Library/Frameworks/AppKit.framework/AppKit", Arena.global());
            var string = appKit.find(name).orElseThrow().reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
            var appearance = (MemorySegment) AppKitMenuProbe.message(objcClass("NSAppearance", arena), "appearanceNamed:", ADDRESS,
                    new java.lang.foreign.MemoryLayout[]{ADDRESS}, string);
            assertNotNullAppearance(appearance);
            AppKitMenuProbe.message(field(sheet, "owner"), "setAppearance:", null,
                    new java.lang.foreign.MemoryLayout[]{ADDRESS}, appearance);
            var effective = (MemorySegment) AppKitMenuProbe.message(field(sheet, "panel"), "effectiveAppearance", ADDRESS);
            var actualName = (MemorySegment) AppKitMenuProbe.message(effective, "name", ADDRESS);
            if ((byte) AppKitMenuProbe.message(actualName, "isEqualToString:", JAVA_BYTE,
                    new java.lang.foreign.MemoryLayout[]{ADDRESS}, string) == 0)
                throw new AssertionError("Sheet appearance: " + nativeString(actualName)
                        + ", requested: " + nativeString(string));
        }
    }

    private static String nativeString(MemorySegment string) {
        var bytes = (MemorySegment) AppKitMenuProbe.message(string, "UTF8String", ADDRESS);
        return bytes.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private static void assertNotNullAppearance(MemorySegment appearance) {
        if (appearance.address() == 0) throw new AssertionError("Appearance must exist");
    }
}
