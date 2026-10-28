package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.math.PreciseComplex;
import com.shangin.fractal.render.RenderTarget;
import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.InteractiveRenderMode;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Modality;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.MAC)
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class MacModalWindowsFxTest {
    private Stage owner;

    @BeforeAll static void start() throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }
    @AfterAll static void stop() { Platform.exit(); }
    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
    @BeforeEach void showOwner() throws Exception {
        owner = fx(() -> {
            assertTrue(MacParameterSheet.isAvailable());
            var stage = new Stage();
            stage.setScene(new Scene(new StackPane(), 640, 440));
            stage.show();
            return stage;
        });
    }
    @AfterEach void close() throws Exception {
        fx(() -> { FractalDialogs.close(owner); owner.close(); return null; });
    }

    @Test void coordinatesRetainExactDraftsAndApplyOnFxThread() throws Exception {
        var result = new CompletableFuture<PreciseComplex>();
        var sheet = fx(() -> MacFormSheet.coordinates(owner, new PreciseComplex("0", "0"),
                FractalDialogs.coordinateHint(new BigDecimal("1E30"), true),
                value -> { assertTrue(Platform.isFxApplicationThread()); result.complete(value); }));
        fx(() -> {
            AppKitParameterProbe.text(sheet, "first", "NaN");
            AppKitParameterProbe.action(sheet, "apply");
            assertEquals("NaN", AppKitParameterProbe.text(sheet, "first"));
            assertTrue(AppKitParameterProbe.showing(sheet));
            AppKitParameterProbe.text(sheet, "first", "-0.743643887037151000000000000000001");
            AppKitParameterProbe.text(sheet, "second", "0.131825904205330000000000000000002");
            AppKitParameterProbe.action(sheet, "apply");
            return null;
        });
        assertEquals(new PreciseComplex("-0.743643887037151000000000000000001",
                "0.131825904205330000000000000000002"), result.get(10, TimeUnit.SECONDS));
    }

    @Test void exportValidatesDraftsAndDiscardsResultAfterOwnerClosure() throws Exception {
        var applied = new AtomicInteger();
        fx(() -> {
            var sheet = MacFormSheet.export(owner, new RenderTarget(640, 440), ignored -> applied.incrementAndGet());
            for (String appearance : List.of("NSAppearanceNameAqua", "NSAppearanceNameDarkAqua")) {
                AppKitParameterProbe.appearance(sheet, appearance);
                for (String invalid : List.of("1", "2.5", "2147483648", "1000000000")) {
                    AppKitParameterProbe.text(sheet, "first", invalid);
                    AppKitParameterProbe.action(sheet, "apply");
                    assertTrue(AppKitParameterProbe.showing(sheet));
                    assertEquals(invalid, AppKitParameterProbe.text(sheet, "first"));
                }
            }
            AppKitParameterProbe.text(sheet, "first", "800");
            AppKitParameterProbe.action(sheet, "apply");
            owner.close();
            sheet.close();
            sheet.close();
            return null;
        });
        fx(() -> { assertEquals(0, applied.get()); return null; });
    }

    @Test void paletteAddsRemovesValidatesAndPreservesArgb() throws Exception {
        var result = new CompletableFuture<List<ColorStop>>();
        var initial = List.of(new ColorStop(0, 0x20804020), new ColorStop(1, 0xff123456));
        var sheet = fx(() -> MacPaletteSheet.show(owner, initial, result::complete));
        fx(() -> {
            assertArrayEquals(new double[]{128 / 255.0, 64 / 255.0, 32 / 255.0, 32 / 255.0},
                    AppKitParameterProbe.previewRgba(sheet, 0), 1 / 255.0,
                    "Native preview must preserve straight RGB and alpha for translucent stops");
            AppKitParameterProbe.action(sheet, "addStop");
            var positions = AppKitParameterProbe.field(sheet, "positions");
            assertEquals(3L, AppKitMenuProbe.message(positions, "count", JAVA_LONG));
            var first = (java.lang.foreign.MemorySegment) AppKitMenuProbe.message(positions, "firstObject", ADDRESS);
            AppKitParameterProbe.setText(first, "-0.1");
            AppKitParameterProbe.action(sheet, "apply");
            assertTrue(AppKitParameterProbe.showing(sheet));
            AppKitParameterProbe.setText(first, "0\n1");
            AppKitParameterProbe.action(sheet, "apply");
            assertTrue(AppKitParameterProbe.showing(sheet));
            AppKitParameterProbe.setText(first, "0.25");
            var remove = (java.lang.foreign.MemorySegment) AppKitMenuProbe.message(
                    AppKitParameterProbe.field(sheet, "removeButtons"), "lastObject", ADDRESS);
            AppKitMenuProbe.message(AppKitParameterProbe.session(sheet), "removeStop:", null,
                    new java.lang.foreign.MemoryLayout[]{ADDRESS}, remove);
            assertEquals(2L, AppKitMenuProbe.message(positions, "count", JAVA_LONG));
            AppKitParameterProbe.action(sheet, "apply");
            return null;
        });
        assertEquals(List.of(new ColorStop(0.25, initial.getFirst().color()), initial.getLast()),
                result.get(10, TimeUnit.SECONDS));
    }

    @Test void queuedErrorsAndCloseConfirmationUseNativeAlerts() throws Exception {
        var editor = fx(() -> MacFormSheet.export(owner, new RenderTarget(640, 440), ignored -> {}));
        fx(() -> {
            FractalDialogs.message(owner, "Render failed", "Try another view.");
            assertSame(editor, AppKitParameterProbe.activeSheet(owner));
            editor.close();
            return null;
        });
        fx(() -> null);
        fx(() -> {
            var alert = AppKitParameterProbe.activeSheet(owner);
            assertInstanceOf(MacNoticeSheet.class, alert);
            AppKitParameterProbe.clickAlert(alert, false);
            return null;
        });
        fx(() -> null);
        var result = new CompletableFuture<Boolean>();
        fx(() -> { FractalDialogs.confirmExportClose(owner, result::complete); return null; });
        fx(() -> {
            AppKitParameterProbe.clickAlert(AppKitParameterProbe.activeSheet(owner), true);
            return null;
        });
        assertTrue(result.get(10, TimeUnit.SECONDS));
    }

    @Test void settingsSelectStandardAndDeepZoomModesFromPopUpButtons() throws Exception {
        var result = new CompletableFuture<AntialiasSettings>();
        var current = new AntialiasSettings(InteractiveRenderMode.REFINED, InteractiveRenderMode.BASE_ONLY);
        var sheet = fx(() -> MacSettingsSheet.show(owner, current,
                value -> { assertTrue(Platform.isFxApplicationThread()); result.complete(value); }));
        fx(() -> {
            assertTrue(MacParameterSheet.hasOpenSheet(owner));
            var standard = AppKitParameterProbe.choice(sheet, 0);
            var deep = AppKitParameterProbe.choice(sheet, 1);
            assertEquals(SettingsWindow.index(InteractiveRenderMode.REFINED), AppKitParameterProbe.selectedIndex(standard));
            assertEquals(SettingsWindow.index(InteractiveRenderMode.BASE_ONLY), AppKitParameterProbe.selectedIndex(deep));
            assertTrue(AppKitParameterProbe.enabled(deep), "The deep zoom mode is editable at any zoom");
            for (String appearance : List.of("NSAppearanceNameAqua", "NSAppearanceNameDarkAqua")) {
                AppKitParameterProbe.appearance(sheet, appearance);
            }
            AppKitParameterProbe.select(standard, SettingsWindow.index(InteractiveRenderMode.BASE_ONLY));
            AppKitParameterProbe.select(deep, SettingsWindow.index(InteractiveRenderMode.REFINED));
            AppKitParameterProbe.alertKey(sheet, "\r", (short) 36);
            return null;
        });
        assertEquals(new AntialiasSettings(InteractiveRenderMode.BASE_ONLY, InteractiveRenderMode.REFINED),
                result.get(10, TimeUnit.SECONDS));
        fx(() -> { assertFalse(sheet.isOpen()); return null; });
    }

    @Test void settingsTabStopsAreOnlyControls() throws Exception {
        var sheet = fx(() -> MacSettingsSheet.show(owner, new AntialiasSettings(), ignored -> {}));
        fx(() -> {
            // A clicked selectable label keeps keyboard focus, so Tab would select its text instead of moving
            // between the pop-up buttons and buttons.
            assertEquals(List.of(), AppKitParameterProbe.selectableTexts(sheet));
            AppKitParameterProbe.action(sheet, "cancel");
            return null;
        });
    }

    @Test void settingsFocusOnlyAKeyboardNavigablePopUpButton() throws Exception {
        var sheet = fx(() -> MacSettingsSheet.show(owner, new AntialiasSettings(), ignored -> {}));
        fx(() -> {
            // Checks the macOS Keyboard navigation setting of this desktop session: when it is off,
            // a focused pop-up button keeps a stale focus ring while clicks select other controls.
            var first = AppKitParameterProbe.choice(sheet, 0);
            boolean navigable = AppKitParameterProbe.canBecomeKeyView(first);
            assertEquals(navigable, AppKitParameterProbe.isFirstResponder(sheet, first));
            AppKitParameterProbe.action(sheet, "resetChoices");
            assertEquals(navigable, AppKitParameterProbe.isFirstResponder(sheet, first));
            AppKitParameterProbe.action(sheet, "cancel");
            return null;
        });
    }

    @Test void settingsResetToDefaultsChangesOnlyTheDraft() throws Exception {
        var result = new CompletableFuture<AntialiasSettings>();
        var sheet = fx(() -> MacSettingsSheet.show(owner,
                new AntialiasSettings(InteractiveRenderMode.FAST, InteractiveRenderMode.REFINED), result::complete));
        fx(() -> {
            AppKitParameterProbe.action(sheet, "resetChoices");
            assertTrue(sheet.isOpen(), "Reset must not close the sheet");
            assertFalse(result.isDone());
            assertEquals(SettingsWindow.index(InteractiveRenderMode.REFINED),
                    AppKitParameterProbe.selectedIndex(AppKitParameterProbe.choice(sheet, 0)));
            assertEquals(SettingsWindow.index(InteractiveRenderMode.BASE_ONLY),
                    AppKitParameterProbe.selectedIndex(AppKitParameterProbe.choice(sheet, 1)));
            AppKitParameterProbe.action(sheet, "apply");
            return null;
        });
        assertEquals(new AntialiasSettings(), result.get(10, TimeUnit.SECONDS));
    }

    @Test void settingsEscapeAndCancelDiscardDraft() throws Exception {
        var applied = new AtomicInteger();
        var current = new AntialiasSettings(InteractiveRenderMode.FAST, InteractiveRenderMode.BASE_ONLY);
        for (boolean escape : List.of(true, false)) {
            var sheet = fx(() -> MacSettingsSheet.show(owner, current, ignored -> applied.incrementAndGet()));
            fx(() -> {
                AppKitParameterProbe.select(AppKitParameterProbe.choice(sheet, 0), SettingsWindow.index(InteractiveRenderMode.REFINED));
                AppKitParameterProbe.select(AppKitParameterProbe.choice(sheet, 1), SettingsWindow.index(InteractiveRenderMode.FAST));
                if (escape) AppKitParameterProbe.alertKey(sheet, "\u001b", (short) 53);
                else AppKitParameterProbe.action(sheet, "cancel");
                return null;
            });
            fx(() -> null);
            fx(() -> {
                assertFalse(sheet.isOpen());
                assertFalse(MacParameterSheet.hasOpenSheet(owner));
                assertEquals(0, applied.get());
                return null;
            });
        }
        var result = new CompletableFuture<AntialiasSettings>();
        var sheet = fx(() -> MacSettingsSheet.show(owner, current, result::complete));
        fx(() -> {
            AppKitParameterProbe.select(AppKitParameterProbe.choice(sheet, 1), SettingsWindow.index(InteractiveRenderMode.FAST));
            AppKitParameterProbe.action(sheet, "apply");
            return null;
        });
        assertEquals(new AntialiasSettings(InteractiveRenderMode.FAST, InteractiveRenderMode.FAST),
                result.get(10, TimeUnit.SECONDS));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"\u001b", "\r"})
    void closeConfirmationKeyboardReturnsSafely(String key) throws Exception {
        var result = new CompletableFuture<Boolean>();
        var sheet = fx(() -> MacNoticeSheet.show(owner, "Close FractalLens?",
                "A PNG export is still in progress. Closing will cancel it.", true, false, result::complete));
        try {
            fx(() -> {
                AppKitParameterProbe.alertKey(sheet, key, (short) (key.equals("\u001b") ? 53 : 36));
                return null;
            });
            assertFalse(result.get(10, TimeUnit.SECONDS), "Keyboard dismissal must preserve the open application");
            fx(() -> {
                assertFalse(sheet.isOpen());
                assertFalse(MacParameterSheet.hasOpenSheet(owner));
                assertTrue(owner.isShowing());
                return null;
            });
        } finally {
            fx(() -> { sheet.close(); return null; });
        }
    }

    @Test void alertsWaitForEditorsAndHelpDoesNotBlockThem() throws Exception {
        var ran = new AtomicInteger();
        var sheet = fx(() -> MacFormSheet.export(owner, new RenderTarget(640, 440), ignored -> {}));
        fx(() -> {
            MacParameterSheet.whenAvailable(owner, ran::incrementAndGet);
            assertEquals(0, ran.get());
            FractalDialogs.exportSize(owner, new RenderTarget(800, 600), ignored -> fail("Overlapping editor"));
            assertTrue(sheet.isOpen());
            AppKitParameterProbe.action(sheet, "cancel");
            return null;
        });
        fx(() -> null);
        fx(() -> { assertEquals(1, ran.get()); return null; });
        var result = new CompletableFuture<Boolean>();
        var alert = fx(() -> MacNoticeSheet.show(owner, "Close?", "Export in progress", true, false, result::complete));
        fx(() -> {
            var buttons = (java.lang.foreign.MemorySegment) AppKitMenuProbe.message(
                    AppKitParameterProbe.field(alert, "alert"), "buttons", ADDRESS);
            var cancel = (java.lang.foreign.MemorySegment) AppKitMenuProbe.message(buttons, "firstObject", ADDRESS);
            AppKitMenuProbe.message(cancel, "performClick:", null,
                    new java.lang.foreign.MemoryLayout[]{ADDRESS}, java.lang.foreign.MemorySegment.NULL);
            return null;
        });
        assertFalse(result.get(10, TimeUnit.SECONDS));
        fx(() -> {
            var help = MacNoticeSheet.show(owner, "Help", "Explore", false, true, ignored -> {});
            assertFalse(MacParameterSheet.hasOpenSheet(owner));
            assertEquals(Modality.NONE, FractalDialogs.helpDialog(owner).getModality());
            var editor = MacFormSheet.export(owner, new RenderTarget(640, 440), ignored -> {});
            assertTrue(editor.isOpen());
            help.close();
            editor.close();
            return null;
        });
    }
}
