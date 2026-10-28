package com.shangin.fractal.ui;

import com.shangin.fractal.app.LastSessionStore;
import com.shangin.fractal.render.DirectDoubleRenderBackend;
import com.shangin.fractal.render.FractalRenderService;
import com.shangin.fractal.render.RenderBackend;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderJob;
import com.shangin.fractal.render.RenderRegion;
import com.shangin.fractal.render.TileTimingStats;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Manual desktop acceptance driver. No screen markers, snapshots, or repaint requests. */
public final class SpaceSwitchProbe {
    private SpaceSwitchProbe() {}

    public static void main(String[] args) {
        if (args.length != 1 || !System.getProperty("os.name", "").startsWith("Mac")) {
            throw new IllegalArgumentException("Expected an isolated session-file path on macOS");
        }
        String pipeline = System.getProperty("prism.order", "");
        if (!pipeline.equals("es2") && !pipeline.equals("mtl")) {
            throw new IllegalArgumentException("Request exactly one pipeline: es2 or mtl");
        }
        Platform.startup(() -> start(Path.of(args[0])));
    }

    private static void start(Path sessionFile) {
        Stage stage = new Stage();
        FocusGatedBackend backend = new FocusGatedBackend();
        MainView main = new MainView(stage, new LastSessionStore(sessionFile),
                new FractalRenderService(backend));
        FractalView view = ((StackPane) main.getCenter()).getChildren().stream()
                .filter(FractalView.class::isInstance).map(FractalView.class::cast)
                .findFirst().orElseThrow();
        stage.setScene(new Scene(main, 1200, 760));
        stage.focusedProperty().addListener((observable, previous, focused) -> {
            backend.focusChanged(focused);
            log("focus=" + focused);
        });
        stage.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() != KeyCode.F7 && event.getCode() != KeyCode.F8
                    && event.getCode() != KeyCode.F9) return;
            event.consume();
            if (!view.isLatencyIdle() || !view.hasCompletedFrame()) {
                log("rejected: wait for the completed frame");
                return;
            }
            if (event.getCode() != KeyCode.F9) {
                view.requestFocus();
                backend.arm();
                view.latency().arm(publication -> {
                    if (publication.stage().equals("frame_promoted") || publication.stage().equals("complete")) {
                        log("trial=" + backend.trialNumber() + " stage=" + publication.stage()
                                + " render=" + publication.render() + " focused=" + stage.isFocused()
                                + " " + view.latencyFixture());
                    }
                });
                if (event.getCode() == KeyCode.F8) {
                    int direction = (backend.trialNumber() - 1) % 4;
                    int dx = direction == 0 ? 120 : direction == 1 ? -120 : 0;
                    int dy = direction == 2 ? 120 : direction == 3 ? -120 : 0;
                    log("controlled_drag trial=" + backend.trialNumber() + " dx=" + dx + " dy=" + dy);
                    mouse(view, MouseEvent.MOUSE_PRESSED, 0, 0, true);
                    mouse(view, MouseEvent.MOUSE_DRAGGED, dx, dy, true);
                    mouse(view, MouseEvent.MOUSE_RELEASED, dx, dy, false);
                }
            } else {
                // Observe only after the unaided visual verdict; this key is not part of acceptance.
                log("checkpoint trial=" + backend.trialNumber() + " focus_owner="
                        + stage.getScene().getFocusOwner() + " " + view.latencyFixture());
            }
        });
        stage.setOnHidden(event -> {
            main.close();
            Platform.exit();
        });
        stage.show();
        stage.centerOnScreen();
        main.requestFocus();
        log("started requested_pipeline=" + System.getProperty("prism.order")
                + "; F8 arms and drags; F7 arms a manual pan; F9 logs a checkpoint");
    }

    private static void mouse(FractalView view, EventType<MouseEvent> type, int dx, int dy, boolean down) {
        Event.fireEvent(view, new MouseEvent(type, view.getWidth() / 2 + dx, view.getHeight() / 2 + dy,
                0, 0, MouseButton.PRIMARY, 1, false, false, false, false,
                down, false, false, false, false, true, null));
    }

    private static void log(String message) {
        System.out.println("SPACE_PROBE " + System.nanoTime() + " " + message);
    }

    /** Holds calculation, never the FX thread, until three seconds after focus loss. */
    static final class FocusGatedBackend implements RenderBackend {
        private final RenderBackend delegate;
        private final ScheduledExecutorService scheduler;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Trial trial;
        private volatile boolean focused;
        private int number;

        FocusGatedBackend() {
            this(new DirectDoubleRenderBackend(), Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "space-probe-release");
                thread.setDaemon(true);
                return thread;
            }));
        }

        FocusGatedBackend(RenderBackend delegate, ScheduledExecutorService scheduler) {
            this.delegate = java.util.Objects.requireNonNull(delegate);
            this.scheduler = java.util.Objects.requireNonNull(scheduler);
        }

        CompletableFuture<Void> arm() {
            trial = new Trial(++number);
            log("armed trial=" + number);
            return trial.waiting;
        }

        int trialNumber() { return number; }

        void focusChanged(boolean value) {
            if (closed.get()) return;
            focused = value;
            Trial captured = trial;
            if (!value && captured != null && captured.scheduled.compareAndSet(false, true)) {
                scheduler.schedule(() -> {
                    log("release trial=" + captured.number + " focused=" + focused);
                    captured.release.countDown();
                }, 3, TimeUnit.SECONDS);
            }
        }

        @Override
        public boolean supports(RenderJob job) { return delegate.supports(job); }

        @Override
        public RenderFrame render(RenderFrame frame, BooleanSupplier cancelled,
                                  Consumer<RenderRegion> regionCompleted,
                                  Consumer<TileTimingStats> timingCompleted) throws InterruptedException {
            Trial captured = trial;
            if (captured != null) {
                log("waiting trial=" + captured.number + " focused=" + focused);
                captured.waiting.complete(null);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (!captured.release.await(100, TimeUnit.MILLISECONDS)) {
                    if (cancelled.getAsBoolean() || closed.get()) throw new InterruptedException("Render cancelled");
                    if (System.nanoTime() >= deadline) {
                        throw new IllegalStateException("Trial timed out before focus loss; no acceptance result");
                    }
                }
            }
            if (cancelled.getAsBoolean() || closed.get()) throw new InterruptedException("Render cancelled");
            return delegate.render(frame, cancelled, regionCompleted, timingCompleted);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            Trial captured = trial;
            if (captured != null) captured.release.countDown();
            scheduler.shutdownNow();
            delegate.close();
        }

        private static final class Trial {
            final int number;
            final CountDownLatch release = new CountDownLatch(1);
            final CompletableFuture<Void> waiting = new CompletableFuture<>();
            final AtomicBoolean scheduled = new AtomicBoolean();

            Trial(int number) { this.number = number; }
        }
    }
}
