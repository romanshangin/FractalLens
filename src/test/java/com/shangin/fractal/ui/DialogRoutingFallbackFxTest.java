package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.render.RenderTarget;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Run against an isolated build with the macos-native-menu profile disabled. */
@EnabledIfSystemProperty(named = "fractal.fallback.tests", matches = "true")
class DialogRoutingFallbackFxTest {
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
            assertFalse(MacParameterSheet.isAvailable(), "Fallback run must exclude the native bridge");
            var stage = new Stage();
            stage.setScene(new Scene(new StackPane(new TextField("Canvas owner")), 640, 440));
            stage.show();
            return stage;
        });
    }
    @AfterEach void close() throws Exception {
        fx(() -> { FractalDialogs.close(owner); owner.close(); return null; });
    }
    private static DialogPane pane(String title) {
        return Window.getWindows().stream().filter(Window::isShowing)
                .filter(window -> window instanceof Stage stage && title.equals(stage.getTitle()))
                .map(window -> (DialogPane) window.getScene().getRoot()).findFirst().orElseThrow();
    }
    private static void primary(DialogPane pane) {
        pane.getButtonTypes().stream().map(type -> (Button) pane.lookupButton(type))
                .filter(Button::isDefaultButton).findFirst().orElseThrow().fire();
    }

    @Test void coordinateAndExportCallbacksPreserveExactValues() throws Exception {
        var editor = new RecordingCoordinateEditor(target -> true);
        var closed = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            FractalDialogs.coordinates(owner, new CoordinateText("0", "0", "1"), false, editor);
            closed.complete(null);
        });
        fx(() -> {
            var dialog = pane("Go to Coordinates");
            var zoom = (TextField) dialog.lookup("#coordinate-zoom");
            zoom.setText("0.5");
            primary(dialog);
            assertTrue(dialog.getScene().getWindow().isShowing(), "A failed Go keeps the draft open");
            assertEquals("0.5", zoom.getText());
            assertTrue(editor.applied.isEmpty());
            zoom.setText("1");
            ((TextField) dialog.lookup("#coordinate-real")).setText("-0.743643887037151000000000001");
            primary(dialog);
            // Go applied before the dialog closed.
            assertEquals(List.of(new CoordinateTarget(new BigDecimal("-0.743643887037151000000000001"), null, null)),
                    editor.applied);
            return null;
        });
        closed.get(10, TimeUnit.SECONDS);
        fx(() -> {
            assertEquals(1, editor.applied.size(), "Closing must not apply a second time");
            assertFalse(editor.submittedOffFxThread);
            return null;
        });
        var size = new CompletableFuture<RenderTarget>();
        Platform.runLater(() -> FractalDialogs.exportSize(owner, new RenderTarget(640, 440), size::complete));
        fx(() -> {
            var dialog = pane("Export PNG");
            ((TextField) dialog.lookup("#export-width")).setText("800");
            primary(dialog);
            return null;
        });
        assertEquals(new RenderTarget(800, 440), size.get(10, TimeUnit.SECONDS));
    }

    @Test void paletteAndHelpAreModelessAndPreviewUpdatesWithoutApplying() throws Exception {
        var result = new CompletableFuture<List<ColorStop>>();
        fx(() -> {
            var stops = List.of(new ColorStop(0, 0xff000000), new ColorStop(1, 0xffffffff));
            FractalDialogs.palette(owner, stops, result::complete);
            FractalDialogs.palette(owner, stops, ignored -> fail("Duplicate palette"));
            FractalDialogs.help(owner);
            var palette = pane("Edit Palette");
            assertEquals(Modality.NONE, ((Stage) palette.getScene().getWindow()).getModality());
            var preview = (ImageView) palette.lookup("#palette-preview");
            var before = preview.getImage().getPixelReader().getArgb(100, 0);
            var editor = (PaletteStopEditor) ((ScrollPane) palette.lookup(".scroll-pane")).getContent();
            editor.initialFocus().setText("0.4");
            assertNotEquals(before, preview.getImage().getPixelReader().getArgb(100, 0));
            assertFalse(result.isDone());
            assertEquals(Modality.NONE, ((Stage) pane("FractalLens Help").getScene().getWindow()).getModality());
            primary(palette);
            return null;
        });
        assertEquals(0.4, result.get(10, TimeUnit.SECONDS).getFirst().position());
    }

    @Test void closeConfirmationReturnsCancellationAndErrorsRemainDismissible() throws Exception {
        var result = new CompletableFuture<Boolean>();
        fx(() -> {
            FractalDialogs.confirmExportClose(owner, result::complete);
            var dialog = pane("Export in progress");
            dialog.getButtonTypes().stream().filter(type -> type.getButtonData().isCancelButton())
                    .map(type -> (Button) dialog.lookupButton(type)).findFirst().orElseThrow().fire();
            return null;
        });
        assertFalse(result.get(10, TimeUnit.SECONDS));
        fx(() -> {
            FractalDialogs.message(owner, "Render failed", "Try another view.");
            primary(pane("Render failed"));
            return null;
        });
    }
}
