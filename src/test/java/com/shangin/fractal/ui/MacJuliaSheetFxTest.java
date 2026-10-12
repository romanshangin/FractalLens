package com.shangin.fractal.ui;

import com.shangin.fractal.scene.JuliaParameters;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.MAC)
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class MacJuliaSheetFxTest {
    private Stage owner;
    private MacJuliaSheet sheet;

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
        assertTrue(MacJuliaSheet.isAvailable());
        owner = fx(() -> {
            var stage = new Stage();
            stage.setTitle("FractalLens Julia sheet verification");
            stage.setScene(new Scene(new StackPane(new TextField()), 640, 440));
            stage.show();
            return stage;
        });
    }

    @AfterEach void close() throws Exception {
        fx(() -> {
            if (sheet != null) sheet.close();
            owner.close();
            return null;
        });
    }

    @Test void appliesBothFiniteComponentsOnTheFxThread() throws Exception {
        var result = new CompletableFuture<JuliaParameters>();
        sheet = fx(() -> MacJuliaSheet.show(owner, new JuliaParameters(), value -> {
            assertTrue(Platform.isFxApplicationThread());
            result.complete(value);
        }));
        fx(() -> {
            AppKitParameterProbe.text(sheet, "first", " -4e-1 ");
            AppKitParameterProbe.text(sheet, "second", "0x1.3333333333333p-1");
            assertFalse(result.isDone());
            AppKitParameterProbe.action(sheet, "apply");
            return null;
        });
        assertEquals(new JuliaParameters(-0.4, 0.6), result.get(10, TimeUnit.SECONDS));
        assertFalse(fx(sheet::isOpen));
    }

    @Test void invalidDraftsSurviveAppearanceChangesUntilCorrected() throws Exception {
        var result = new CompletableFuture<JuliaParameters>();
        sheet = fx(() -> MacJuliaSheet.show(owner, new JuliaParameters(), result::complete));
        for (String appearance : List.of("NSAppearanceNameAqua", "NSAppearanceNameDarkAqua")) {
            fx(() -> {
                AppKitParameterProbe.appearance(sheet, appearance);
                for (String field : List.of("first", "second")) {
                    for (String invalid : List.of("", "invalid draft", "NaN", "Infinity", "-Infinity", "1e309")) {
                        AppKitParameterProbe.text(sheet, field, invalid);
                        AppKitParameterProbe.action(sheet, "apply");
                        assertTrue(AppKitParameterProbe.showing(sheet));
                        assertEquals(invalid, AppKitParameterProbe.text(sheet, field));
                        assertFalse(AppKitParameterProbe.text(sheet, "error").isEmpty());
                        assertFalse(result.isDone());
                    }
                    AppKitParameterProbe.text(sheet, field, "0.25");
                }
                return null;
            });
        }
        fx(() -> { AppKitParameterProbe.action(sheet, "apply"); return null; });
        assertEquals(new JuliaParameters(0.25, 0.25), result.get(10, TimeUnit.SECONDS));
    }

    @Test void resetChangesDraftsAndCancelNeverAppliesThem() throws Exception {
        var applied = new AtomicInteger();
        sheet = fx(() -> MacJuliaSheet.show(owner, new JuliaParameters(-0.4, 0.6), ignored -> applied.incrementAndGet()));
        fx(() -> {
            AppKitParameterProbe.text(sheet, "first", "NaN");
            AppKitParameterProbe.action(sheet, "apply");
            AppKitParameterProbe.action(sheet, "reset");
            assertTrue(sheet.isOpen());
            assertEquals("-0.8", AppKitParameterProbe.text(sheet, "first"));
            assertEquals("0.156", AppKitParameterProbe.text(sheet, "second"));
            assertEquals("", AppKitParameterProbe.text(sheet, "error"));
            assertEquals(0, applied.get());
            AppKitParameterProbe.action(sheet, "cancel");
            return null;
        });
        fx(() -> { assertFalse(sheet.isOpen()); assertEquals(0, applied.get()); return null; });
    }

    @Test void ownerClosureAndIdempotentDisposalSuppressPendingApply() throws Exception {
        var applied = new AtomicInteger();
        fx(() -> {
            sheet = MacJuliaSheet.show(owner, new JuliaParameters(), ignored -> applied.incrementAndGet());
            AppKitParameterProbe.action(sheet, "apply");
            sheet.close();
            sheet.close();
            assertFalse(sheet.isOpen());
            return null;
        });
        fx(() -> {
            assertEquals(0, applied.get());
            sheet = MacJuliaSheet.show(owner, new JuliaParameters(), ignored -> applied.incrementAndGet());
            AppKitParameterProbe.action(sheet, "apply");
            owner.close();
            assertFalse(sheet.isOpen());
            return null;
        });
        fx(() -> { assertEquals(0, applied.get()); return null; });
    }
}
