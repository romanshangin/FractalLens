package com.shangin.fractal.ui;

import com.shangin.fractal.app.BookmarkStore;
import javafx.application.Platform;
import javafx.scene.Scene;
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
class MacBookmarkSheetFxTest {
    private Stage owner;
    private MacBookmarkSheet sheet;

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
        assertTrue(MacBookmarkSheet.isAvailable());
        owner = fx(() -> {
            var stage = new Stage();
            stage.setScene(new Scene(new StackPane(), 640, 440));
            stage.show();
            return stage;
        });
    }

    @AfterEach void close() throws Exception {
        fx(() -> { if (sheet != null) sheet.close(); owner.close(); return null; });
    }

    @Test void savesEightyUnicodeCharactersOnFxThread() throws Exception {
        var result = new CompletableFuture<String>();
        sheet = fx(() -> MacBookmarkSheet.show(owner, name -> {
            assertTrue(Platform.isFxApplicationThread());
            result.complete(name);
        }));
        String name = "🌌".repeat(80);
        fx(() -> {
            AppKitParameterProbe.text(sheet, "first", "  " + name + "  ");
            AppKitParameterProbe.action(sheet, "apply");
            return null;
        });
        assertEquals(name, result.get(10, TimeUnit.SECONDS));
        assertFalse(fx(sheet::isOpen));
    }

    @Test void invalidDraftsRemainEditableInBothAppearances() throws Exception {
        var applied = new AtomicInteger();
        sheet = fx(() -> MacBookmarkSheet.show(owner, ignored -> applied.incrementAndGet()));
        for (String appearance : List.of("NSAppearanceNameAqua", "NSAppearanceNameDarkAqua")) {
            fx(() -> {
                AppKitParameterProbe.appearance(sheet, appearance);
                for (String invalid : List.of("", " ", "🌌".repeat(81), "Line\nbreak", "Tab\tname")) {
                    AppKitParameterProbe.text(sheet, "first", invalid);
                    AppKitParameterProbe.action(sheet, "apply");
                    assertTrue(AppKitParameterProbe.showing(sheet));
                    assertEquals(invalid, AppKitParameterProbe.text(sheet, "first"));
                    assertEquals(BookmarkStore.NAME_ERROR, AppKitParameterProbe.text(sheet, "error"));
                    assertEquals(0, applied.get());
                }
                return null;
            });
        }
        fx(() -> { AppKitParameterProbe.action(sheet, "cancel"); return null; });
        fx(() -> { assertFalse(sheet.isOpen()); assertEquals(0, applied.get()); return null; });
    }

    @Test void ownerClosureAndRepeatedDisposalSuppressPendingSave() throws Exception {
        var applied = new AtomicInteger();
        fx(() -> {
            sheet = MacBookmarkSheet.show(owner, ignored -> applied.incrementAndGet());
            AppKitParameterProbe.text(sheet, "first", "Scene");
            AppKitParameterProbe.action(sheet, "apply");
            owner.close();
            sheet.close();
            sheet.close();
            assertFalse(sheet.isOpen());
            return null;
        });
        fx(() -> { assertEquals(0, applied.get()); return null; });
    }
}
