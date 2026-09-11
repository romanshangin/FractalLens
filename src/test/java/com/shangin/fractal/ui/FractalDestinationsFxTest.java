package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.*;
import com.shangin.fractal.math.Viewport;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class FractalDestinationsFxTest {
    @Test void menuChangesWithFormulaAndNavigatesEveryBookmark() throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        var initialized = new CountDownLatch(1);
        var stageRef = new AtomicReference<Stage>();
        var viewRef = new AtomicReference<FractalView>();
        var menuRef = new AtomicReference<MainMenuBar>();
        try {
            var setup = new FutureTask<Void>(() -> {
                var stage = new Stage();
                var palette = PalettePreset.values()[0];
                var view = new FractalView(FractalPreset.MANDELBROT, palette, false);
                stageRef.set(stage); viewRef.set(view);
                var menu = new MainMenuBar(stage, view, FractalPreset.MANDELBROT, palette, () -> {});
                menuRef.set(menu);
                view.setOnRenderingChanged(active -> { if (!active) initialized.countDown(); });
                var root = new BorderPane(view); root.setTop(menu);
                stage.setScene(new Scene(root, 480, 320));
                stage.show();
                return null;
            });
            Platform.runLater(setup); setup.get(10, TimeUnit.SECONDS);
            assertTrue(initialized.await(20, TimeUnit.SECONDS));
            var task = new FutureTask<Void>(() -> {
                var stage = stageRef.get();
                var view = viewRef.get();
                var menu = menuRef.get();
                try {
                    var actual = new AtomicReference<Viewport>();
                    view.setOnViewportChanged(actual::set);
                    Menu go = menu.getMenus().stream().filter(m -> m.getText().equals("Go to")).findFirst().orElseThrow();
                    Menu fractal = menu.getMenus().stream().filter(m -> m.getText().equals("Fractal")).findFirst().orElseThrow();
                    for (var preset : FractalPreset.values()) {
                        fractal.getItems().get(preset.ordinal()).fire();
                        var destinations = FractalDestination.forPreset(preset);
                        assertEquals(destinations.size(), go.getItems().size());
                        for (int i = 0; i < destinations.size(); i++) {
                            assertEquals(destinations.get(i).name(), go.getItems().get(i).getText());
                            go.getItems().get(i).fire();
                            assertEquals(destinations.get(i).viewport(), actual.get());
                        }
                        view.resetView();
                        assertEquals(preset.defaultViewport().center(), actual.get().center());
                    }
                } finally { view.close(); stage.close(); }
                return null;
            });
            Platform.runLater(task);
            task.get(30, TimeUnit.SECONDS);
        } finally { Platform.exit(); }
    }
}
