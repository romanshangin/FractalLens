package com.shangin.fractal.ui;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.FunctionDescriptor;
import static java.lang.foreign.ValueLayout.*;

/** Test-only inspection of the native sheet and its real target/action handlers. */
final class AppKitParameterProbe {
    static void alertKey(MacParameterSheet<?> sheet, String characters, short keyCode) throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            var panel = field(sheet, "panel");
            var string = (MemorySegment) AppKitMenuProbe.message(objcClass("NSString", arena),
                    "stringWithUTF8String:", ADDRESS,
                    new java.lang.foreign.MemoryLayout[]{ADDRESS}, arena.allocateFrom(characters));
            var point = java.lang.foreign.MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE);
            var event = (MemorySegment) AppKitMenuProbe.message(objcClass("NSEvent", arena),
                    "keyEventWithType:location:modifierFlags:timestamp:windowNumber:context:characters:charactersIgnoringModifiers:isARepeat:keyCode:",
                    ADDRESS, new java.lang.foreign.MemoryLayout[]{JAVA_LONG, point, JAVA_LONG, JAVA_DOUBLE,
                            JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, JAVA_BYTE, JAVA_SHORT},
                    10L, arena.allocate(point), 0L, 0.0,
                    (long) AppKitMenuProbe.message(panel, "windowNumber", JAVA_LONG), MemorySegment.NULL,
                    string, string, (byte) 0, keyCode);
            var application = (MemorySegment) AppKitMenuProbe.message(objcClass("NSApplication", arena),
                    "sharedApplication", ADDRESS);
            AppKitMenuProbe.message(application, "sendEvent:", null,
                    new java.lang.foreign.MemoryLayout[]{ADDRESS}, event);
        }
    }

    static MacParameterSheet<?> activeSheet(javafx.stage.Window owner) throws Exception {
        var field = MacParameterSheet.class.getDeclaredField("ACTIVE");
        field.setAccessible(true);
        return ((java.util.Map<?, ?>) field.get(null)).get(owner) instanceof MacParameterSheet<?> sheet ? sheet : null;
    }

    static void clickAlert(MacParameterSheet<?> sheet, boolean accept) throws Exception {
        var buttons = (MemorySegment) AppKitMenuProbe.message(field(sheet, "alert"), "buttons", ADDRESS);
        var button = (MemorySegment) AppKitMenuProbe.message(buttons,
                accept ? "lastObject" : "firstObject", ADDRESS);
        AppKitMenuProbe.message(button, "performClick:", null,
                new java.lang.foreign.MemoryLayout[]{ADDRESS}, MemorySegment.NULL);
    }

    static String alertText(MacParameterSheet<?> sheet, String selector) throws Exception {
        return nativeString((MemorySegment) AppKitMenuProbe.message(field(sheet, "alert"), selector, ADDRESS));
    }

    static double[] previewRgba(MacParameterSheet<?> sheet, long x) throws Exception {
        var image = (MemorySegment) AppKitMenuProbe.message(field(sheet, "preview"), "image", ADDRESS);
        var representations = (MemorySegment) AppKitMenuProbe.message(image, "representations", ADDRESS);
        var bitmap = (MemorySegment) AppKitMenuProbe.message(representations, "firstObject", ADDRESS);
        var color = (MemorySegment) AppKitMenuProbe.message(bitmap, "colorAtX:y:", ADDRESS,
                new java.lang.foreign.MemoryLayout[]{JAVA_LONG, JAVA_LONG}, x, 0L);
        return new double[]{
                (double) AppKitMenuProbe.message(color, "redComponent", JAVA_DOUBLE),
                (double) AppKitMenuProbe.message(color, "greenComponent", JAVA_DOUBLE),
                (double) AppKitMenuProbe.message(color, "blueComponent", JAVA_DOUBLE),
                (double) AppKitMenuProbe.message(color, "alphaComponent", JAVA_DOUBLE)};
    }

    static MacBookmarkSheet bookmarkSheet(MainMenuBar menu) throws Exception {
        var actionsField = MainMenuBar.class.getDeclaredField("sceneActions");
        actionsField.setAccessible(true);
        var sheetField = SceneActions.class.getDeclaredField("bookmarkSheet");
        sheetField.setAccessible(true);
        return (MacBookmarkSheet) sheetField.get(actionsField.get(menu));
    }

    static MacIterationSheet sheet(MainMenuBar menu) throws Exception {
        var field = MainMenuBar.class.getDeclaredField("iterationSheet");
        field.setAccessible(true);
        return (MacIterationSheet) field.get(menu);
    }

    static MacJuliaSheet juliaSheet(MainMenuBar menu) throws Exception {
        var field = MainMenuBar.class.getDeclaredField("juliaSheet");
        field.setAccessible(true);
        return (MacJuliaSheet) field.get(menu);
    }

    static MemorySegment session(MacParameterSheet<?> sheet) throws Exception {
        var field = MacParameterSheet.class.getDeclaredField("handle");
        field.setAccessible(true);
        long handle = field.getLong(sheet);
        if (handle == 0) throw new AssertionError("Native parameter sheet must be open");
        return MemorySegment.ofAddress(handle);
    }

    static MemorySegment field(MacParameterSheet<?> sheet, String name) throws Exception {
        return (MemorySegment) AppKitMenuProbe.message(session(sheet), name, ADDRESS);
    }

    static String text(MacParameterSheet<?> sheet, String name) throws Exception {
        var string = (MemorySegment) AppKitMenuProbe.message(field(sheet, name), "stringValue", ADDRESS);
        var bytes = (MemorySegment) AppKitMenuProbe.message(string, "UTF8String", ADDRESS);
        return bytes.reinterpret(Long.MAX_VALUE).getString(0);
    }

    static void text(MacParameterSheet<?> sheet, String name, String value) throws Exception {
        setText(field(sheet, name), value);
    }

    static void setText(MemorySegment field, String value) {
        try (Arena arena = Arena.ofConfined()) {
            var type = objcClass("NSString", arena);
            var replacement = (MemorySegment) AppKitMenuProbe.message(type, "stringWithUTF8String:", ADDRESS,
                    new java.lang.foreign.MemoryLayout[]{ADDRESS}, arena.allocateFrom(value));
            AppKitMenuProbe.message(field, "setStringValue:", null,
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

    static void action(MacParameterSheet<?> sheet, String action) throws Exception {
        AppKitMenuProbe.message(session(sheet), action + ":", null,
                new java.lang.foreign.MemoryLayout[]{ADDRESS}, MemorySegment.NULL);
    }

    static boolean showing(MacParameterSheet<?> sheet) throws Exception {
        return (byte) AppKitMenuProbe.message(field(sheet, "panel"), "isVisible", JAVA_BYTE) != 0;
    }

    static void appearance(MacParameterSheet<?> sheet, String name) throws Exception {
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
