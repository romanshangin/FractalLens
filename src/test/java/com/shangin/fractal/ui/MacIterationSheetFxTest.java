package com.shangin.fractal.ui;

import com.shangin.fractal.scene.IterationSettings;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.MAC)
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class MacIterationSheetFxTest {
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

    @Test void nativeAppearanceChangesKeepDraftsAndSessionAlive() throws Exception {
        assertTrue(MacIterationSheet.isAvailable());
        var applied = new AtomicInteger();
        Stage owner = fx(() -> {
            var stage = new Stage();
            stage.setTitle("FractalLens sheet appearance verification");
            stage.setScene(new Scene(new StackPane(new TextField()), 640, 440));
            stage.show();
            return stage;
        });
        var sheet = fx(() -> MacIterationSheet.show(owner, new IterationSettings(), ignored -> applied.incrementAndGet()));
        try {
            for (String appearance : java.util.List.of("NSAppearanceNameAqua", "NSAppearanceNameDarkAqua")) {
                fx(() -> {
                    AppKitIterationProbe.appearance(sheet, appearance);
                    AppKitIterationProbe.text(sheet, "base", "invalid draft");
                    AppKitIterationProbe.action(sheet, "apply");
                    assertTrue(sheet.isOpen());
                    assertTrue(AppKitIterationProbe.showing(sheet));
                    assertEquals("invalid draft", AppKitIterationProbe.text(sheet, "base"));
                    return null;
                });
            }
            fx(() -> { AppKitIterationProbe.action(sheet, "cancel"); return null; });
            fx(() -> { assertEquals(0, applied.get()); return null; });
        } finally { fx(() -> { sheet.close(); owner.close(); return null; }); }
    }

    @Test void ownerCloseAndDisposalSuppressPendingApply() throws Exception {
        assertTrue(MacIterationSheet.isAvailable());
        var applied = new AtomicInteger();
        Stage owner = fx(() -> {
            var stage = new Stage();
            stage.setScene(new Scene(new StackPane(new TextField()), 640, 440));
            stage.show();
            return stage;
        });
        try {
            fx(() -> {
                var sheet = MacIterationSheet.show(owner, new IterationSettings(), ignored -> applied.incrementAndGet());
                assertTrue(sheet.isOpen());
                AppKitIterationProbe.action(sheet, "apply");
                sheet.close();
                sheet.close();
                assertFalse(sheet.isOpen());
                return null;
            });
            fx(() -> { assertEquals(0, applied.get()); return null; });
            fx(() -> {
                var sheet = MacIterationSheet.show(owner, new IterationSettings(), ignored -> applied.incrementAndGet());
                owner.close();
                assertFalse(sheet.isOpen());
                sheet.close();
                return null;
            });
            fx(() -> { assertEquals(0, applied.get()); return null; });
        } finally { fx(() -> { owner.close(); return null; }); }
    }
}
