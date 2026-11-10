package com.shangin.fractal.ui;

import com.shangin.fractal.app.LastSessionStore;
import com.shangin.fractal.app.PreferencesStore;
import com.shangin.fractal.scene.WidePreviewSettings;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in integration checks: mvn -Dfractal.fx.tests=true -Dtest=MainViewPreferencesFxTest test */
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class MainViewPreferencesFxTest {
    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stopFx() { Platform.exit(); }

    @Test
    void absentChoiceFollowsTheDefaultAndNothingIsWritten() throws Exception {
        var session = new LastSessionStore(temporaryDirectory.resolve("last-session.json"));
        Stage stage = fx(Stage::new);
        MainView main = fx(() -> new MainView(stage, session));
        try {
            assertEquals(WidePreviewSettings.DEFAULT.enabled(), fx(() -> fractalView(main).widePreviewEnabled()));
        } finally {
            fx(() -> { main.close(); stage.close(); return null; });
        }
        assertFalse(Files.exists(temporaryDirectory.resolve("preferences.properties")),
                "Closing without an explicit choice must not pin the current default");
    }

    @Test
    void savedChoiceIsRestoredAndAnExplicitChangeIsSaved() throws Exception {
        var session = new LastSessionStore(temporaryDirectory.resolve("last-session.json"));
        var preferences = PreferencesStore.besides(session);
        boolean saved = !WidePreviewSettings.DEFAULT.enabled();
        preferences.saveWidePreview(saved);
        Stage stage = fx(Stage::new);
        MainView main = fx(() -> new MainView(stage, session));
        try {
            fx(() -> {
                stage.setScene(new Scene(main, 320, 240));
                stage.show();
                assertEquals(saved, fractalView(main).widePreviewEnabled(), "The explicit choice wins over the default");
                applySettings(main, new SettingsWindow.Values(fractalView(main).sceneSnapshot().antialiasing(), !saved));
                assertEquals(!saved, fractalView(main).widePreviewEnabled());
                return null;
            });
            assertEquals(Optional.of(!saved), preferences.widePreview(), "Settings save the new choice at once");
        } finally {
            fx(() -> { main.close(); stage.close(); return null; });
        }
    }

    @Test
    void sizeAndQualityApplyWithoutChangingTheSceneAndRestoreAfterRestart() throws Exception {
        var session = new LastSessionStore(temporaryDirectory.resolve("last-session.json"));
        var preferences = PreferencesStore.besides(session);
        var selected = new WidePreviewSettings(false, 3.0, 4, WidePreviewSettings.DEFAULT.sampleLimit(), 2_000);
        Stage stage = fx(Stage::new);
        MainView main = fx(() -> new MainView(stage, session));
        try {
            fx(() -> {
                var view = fractalView(main);
                var scene = view.sceneSnapshot();
                var resized = new WidePreviewSettings(view.widePreviewEnabled(), 4.0, 16, WidePreviewSettings.DEFAULT.sampleLimit(), 2_000);
                applySettings(main, new SettingsWindow.Values(scene.antialiasing(), resized));
                assertEquals(resized, view.widePreviewSettings());
                assertEquals(resized, preferences.widePreviewSettings(), "Size and quality alone are saved");
                applySettings(main, new SettingsWindow.Values(scene.antialiasing(), selected));
                assertEquals(selected, view.widePreviewSettings());
                assertEquals(scene, view.sceneSnapshot());
                assertFalse(view.canUndo());
                view.setWidePreviewEnabled(true);
                assertEquals(3.0, view.widePreviewSettings().extent());
                assertEquals(4, view.widePreviewSettings().pixelStep());
                return null;
            });
            assertEquals(selected, preferences.widePreviewSettings());
        } finally {
            fx(() -> { main.close(); stage.close(); return null; });
        }
        Stage restartedStage = fx(Stage::new);
        MainView restarted = fx(() -> new MainView(restartedStage, session));
        try {
            assertEquals(selected, fx(() -> fractalView(restarted).widePreviewSettings()));
        } finally {
            fx(() -> { restarted.close(); restartedStage.close(); return null; });
        }
    }

    /** Settings Apply, without depending on which of the native or JavaFX editors is available. */
    private static void applySettings(MainView main, SettingsWindow.Values values) throws Exception {
        var menuField = MainView.class.getDeclaredField("menuBar");
        menuField.setAccessible(true);
        Method apply = MainMenuBar.class.getDeclaredMethod("applySettings", SettingsWindow.Values.class);
        apply.setAccessible(true);
        apply.invoke(menuField.get(main), values);
    }

    private static FractalView fractalView(MainView main) {
        return ((StackPane) main.getCenter()).getChildren().stream()
                .filter(FractalView.class::isInstance)
                .map(FractalView.class::cast).findFirst().orElseThrow();
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
}
