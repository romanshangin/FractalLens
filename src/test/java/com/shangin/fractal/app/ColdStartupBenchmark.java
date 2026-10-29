package com.shangin.fractal.app;

import javafx.application.Platform;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicBoolean;

/** Reports process-visible milestones while launching the actual application. */
public final class ColdStartupBenchmark {

    public static void main(String[] args) {
        FractalLauncher.launch(StartupApplication.class, args);
    }

    public static final class StartupApplication extends FractalFxApplication {
        @Override
        public void start(Stage stage) {
            super.start(stage);
            System.out.println("FRACTALLENS_STAGE_SHOWN_EPOCH_MS=" + System.currentTimeMillis());
            System.out.flush();

            AtomicBoolean reported = new AtomicBoolean();
            stage.getScene().addPostLayoutPulseListener(() -> {
                if (!reported.compareAndSet(false, true)) return;
                System.out.println("FRACTALLENS_FIRST_LAYOUT_PULSE_EPOCH_MS=" + System.currentTimeMillis());
                System.out.flush();
                Platform.runLater(() -> {
                    stage.close();
                    Platform.exit();
                });
            });
            Platform.requestNextPulse();
        }
    }
}
