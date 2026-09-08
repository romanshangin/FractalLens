package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.scene.InteractiveRenderMode;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Exercises real FractalView handlers in a visible window, with compositor-marker
 * readback as an explicitly labelled proxy for presentation (not panel scanout).
 * Input is synthetic JavaFX dispatch: hardware/OS input latency is excluded.
 */
public final class InteractionLatencyBenchmark extends Application {
    private static final List<String> STAGES = List.of("preview_publish", "base_publish", "aa_publish", "complete");
    private final Rectangle[][] markers = new Rectangle[4][8];
    private final Map<Integer, InteractionLatency.Event> markerEvents = new HashMap<>();
    private final Set<Integer> observedCodes = new HashSet<>();
    private final int[] currentCodes = new int[4];
    private int nextCode;
    private final Map<Integer, InteractionLatency.Event> pendingPulse = new HashMap<>();
    private final Map<String, Long> screenSeen = new HashMap<>();
    private final List<String> rows = new ArrayList<>();
    private FractalView view;
    private HBox strip;
    private Robot robot;
    private AnimationTimer timer;
    private WritableImage capture;
    private long lastProbe;
    private long largestProbeGap;
    private long probeWork;
    private boolean recording;
    private boolean screenValidated;
    private boolean screenBlack;
    private static volatile Throwable failure;

    public static void main(String[] args) {
        launch(args);
        if (failure != null) throw new IllegalStateException("Latency benchmark failed", failure);
    }

    @Override public void start(Stage stage) {
        view = new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE);
        strip = new HBox();
        strip.getChildren().add(new Rectangle(16, 16, Color.MAGENTA));
        for (int i = 0; i < markers.length; i++) {
            for (int bit = 0; bit < 8; bit++) {
                markers[i][bit] = new Rectangle(4, 16, Color.BLACK);
                strip.getChildren().add(markers[i][bit]);
            }
        }
        BorderPane root = new BorderPane(view);
        root.setTop(strip);
        Scene scene = new Scene(root, Integer.getInteger("fractal.latency.width", 1200),
                Integer.getInteger("fractal.latency.height", 760) + 16);
        scene.addPostLayoutPulseListener(() -> {
            if (recording) {
                for (var mark : pendingPulse.values()) view.latency().observed(mark, "_post_layout");
            }
            pendingPulse.clear();
        });
        stage.setTitle("FractalUI — interaction latency diagnostic");
        stage.setScene(scene);
        stage.setAlwaysOnTop(true);
        stage.show();
        stage.toFront();
        robot = new Robot();
        timer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (Boolean.getBoolean("fractal.latency.noScreen")) return;
                try { probe(); }
                catch (Throwable error) { failure = error; timer.stop(); }
            }
        };
        timer.start();
        new Thread(() -> run(stage), "interaction-latency-driver").start();
    }

    private void probe() {
        Point2D point = strip.localToScreen(0, 0);
        if (point == null) return;
        long started = System.nanoTime();
        capture = robot.getScreenCapture(capture, point.getX(), point.getY(), 144, 16, true);
        long observed = System.nanoTime();
        Color sentinel = capture.getPixelReader().getColor(8, 8);
        if (!(sentinel.getRed() > .7 && sentinel.getBlue() > .7 && sentinel.getGreen() < .3)) return;
        screenValidated = true;
        screenBlack = true;
        for (int i = 0; i < markers.length; i++) screenBlack &= readCode(i) == 0;
        if (!recording) return;
        if (lastProbe != 0) largestProbeGap = Math.max(largestProbeGap, observed - lastProbe);
        lastProbe = observed;
        probeWork += observed - started;
        for (int i = 0; i < markers.length; i++) {
            int code = readCode(i);
            var event = markerEvents.get(code);
            if (event != null && event.stage().equals(STAGES.get(i)) && observedCodes.add(code)) {
                screenSeen.put(event.stage() + "/" + event.input() + "/" + event.render(), observed);
                view.latency().observed(event, "_screen_observed");
            }
        }
    }

    private int readCode(int index) {
        int code = 0;
        for (int bit = 0; bit < 8; bit++) {
            double brightness = capture.getPixelReader().getColor(18 + index * 32 + bit * 4, 8).getBrightness();
            if (brightness > .8) code |= 1 << bit;
            else if (brightness >= .2) return -1;
        }
        return code;
    }

    private void published(InteractionLatency.Event event) {
        int index = STAGES.indexOf(event.stage());
        if (index < 0) return;
        if (++nextCode > 255) throw new IllegalStateException("Too many marker publications in one trial");
        int code = nextCode;
        markerEvents.put(code, event);
        currentCodes[index] = code;
        for (int bit = 0; bit < 8; bit++) markers[index][bit].setFill((code & (1 << bit)) == 0 ? Color.BLACK : Color.WHITE);
        // Only the last code before a pulse can be observed. Superseded codes
        // remain unobserved; never attribute an old frame to a newer gesture.
        pendingPulse.put(index, event);
    }

    private void run(Stage stage) {
        Path output = Path.of(System.getProperty("fractal.latency.output", "target/interaction-latency.csv"));
        try {
            int samples = Integer.getInteger("fractal.latency.samples", 10);
            int warmups = Integer.getInteger("fractal.latency.warmup", 2);
            if (samples < 1 || warmups < 0) throw new IllegalArgumentException("Invalid sample counts");
            awaitIdle();
            Thread.sleep(300);
            if (!Boolean.getBoolean("fractal.latency.noScreen") && !fx(() -> screenValidated)) {
                throw new IllegalStateException("Screen marker not readable. Check screen-recording permission and window visibility; no screen latency can be reported.");
            }
            rows.add("# Java=" + System.getProperty("java.version") + "; JavaFX=" + System.getProperty("javafx.runtime.version")
                    + "; os=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                    + "; requestedPipeline=" + System.getProperty("prism.order", "default")
                    + "; screenProbe=" + !Boolean.getBoolean("fractal.latency.noScreen")
                    + "; time=" + java.time.Instant.now());
            rows.add("# " + fx(() -> "logical=" + view.getWidth() + "x" + view.getHeight()
                    + "; outputScale=" + stage.getOutputScaleX() + "x" + stage.getOutputScaleY()));
            rows.add("mode,gesture,phase,iteration,input,render,stage,nanos,duration_nanos");
            for (InteractiveRenderMode mode : InteractiveRenderMode.values()) {
                fx(() -> { view.setInteractiveRenderMode(mode); return null; });
                awaitIdle();
                for (String gesture : System.getProperty("fractal.latency.gestures", "wheel,wheel-burst,pinch,trackpad,drag").split(",")) {
                    for (int iteration = -warmups; iteration < samples; iteration++) {
                        fx(() -> { view.resetView(); return null; });
                        awaitIdle();
                        if (gesture.equals("trackpad") || gesture.equals("drag")) {
                            // The home view is clamped to preset bounds and cannot pan.
                            fx(() -> { view.zoom(true); view.zoom(true); return null; });
                            awaitIdle();
                        }
                        fx(() -> {
                            for (Rectangle[] group : markers) for (Rectangle marker : group) marker.setFill(Color.BLACK);
                            markerEvents.clear(); observedCodes.clear(); Arrays.fill(currentCodes, 0); nextCode = 0;
                            screenSeen.clear(); pendingPulse.clear(); screenBlack = false;
                            lastProbe = largestProbeGap = probeWork = 0;
                            return null;
                        });
                        Thread.sleep(120);
                        if (!Boolean.getBoolean("fractal.latency.noScreen")) {
                            long resetDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                            while (!fx(() -> screenBlack)) {
                                if (System.nanoTime() > resetDeadline) throw new IllegalStateException("Black marker reset was not observed; refusing stale screen timestamps");
                                Thread.sleep(20);
                            }
                        }
                        fx(() -> {
                            view.latency().arm(this::published);
                            recording = true;
                            return null;
                        });
                        perform(gesture);
                        awaitIdle();
                        fx(() -> view.latency().diagnosticsDrained()).get(180, TimeUnit.SECONDS);
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                        if (!Boolean.getBoolean("fractal.latency.noScreen")) {
                            while (!fx(() -> currentCodes[3] != 0 && observedCodes.contains(currentCodes[3]))) {
                                if (System.nanoTime() > deadline) throw new IllegalStateException("Completion marker did not reach the screen");
                                Thread.sleep(20);
                            }
                        } else Thread.sleep(80);
                        List<InteractionLatency.Event> events = fx(() -> {
                            view.latency().boundary("trial_end");
                            recording = false;
                            return view.latency().stop();
                        });
                        long lastInput = events.stream().filter(e -> e.stage().startsWith("input_")).mapToLong(InteractionLatency.Event::input).max().orElseThrow();
                        if (events.stream().noneMatch(e -> e.stage().equals("complete") && e.input() == lastInput)) {
                            throw new IllegalStateException("Gesture did not complete a render for its last input");
                        }
                        String prefix = mode.name() + "," + gesture + "," + (iteration < 0 ? "warmup" : "sample") + "," + iteration + ",";
                        rows.add("# fixture=" + mode.name() + "/" + gesture + "/" + iteration + "; " + fx(view::latencyFixture));
                        for (var event : events) rows.add(prefix + event.input() + "," + event.render() + "," + event.stage() + "," + event.nanos() + "," + event.durationNanos());
                        rows.add(prefix + "0,0,screen_probe_max_gap,0," + largestProbeGap);
                        rows.add(prefix + "0,0,screen_probe_total_work,0," + probeWork);
                        System.out.println(mode + " " + gesture + " " + iteration + " complete");
                    }
                }
            }
        } catch (Throwable error) {
            failure = error;
            error.printStackTrace();
            try {
                var partial = fx(() -> {
                    boolean wasRecording = recording;
                    recording = false;
                    if (capture != null) {
                        var dump = new java.awt.image.BufferedImage((int) capture.getWidth(), (int) capture.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                        for (int y = 0; y < dump.getHeight(); y++) for (int x = 0; x < dump.getWidth(); x++) dump.setRGB(x, y, capture.getPixelReader().getArgb(x, y));
                        javax.imageio.ImageIO.write(dump, "png", Path.of("target/latency-marker-failure.png").toFile());
                    }
                    System.err.println("screenSeen=" + screenSeen + "; markers=" + Arrays.toString(currentCodes));
                    return wasRecording ? view.latency().stop() : List.<InteractionLatency.Event>of();
                });
                rows.add("# FAILED: " + error);
                for (var event : partial) {
                    rows.add("FAILED,unknown,failed,0," + event.input() + "," + event.render() + "," + event.stage() + "," + event.nanos() + "," + event.durationNanos());
                    if (!event.stage().endsWith("_queue") && !event.stage().endsWith("_fx_work")) System.err.println(event);
                }
            } catch (Exception diagnosticError) { diagnosticError.printStackTrace(); }
        } finally {
            try {
                if (output.toAbsolutePath().getParent() != null) Files.createDirectories(output.toAbsolutePath().getParent());
                Files.write(output, rows);
                System.out.println("Saved " + output.toAbsolutePath());
            } catch (Exception error) { failure = error; error.printStackTrace(); }
            Platform.runLater(() -> { timer.stop(); view.close(); stage.close(); Platform.exit(); });
        }
    }

    private void perform(String gesture) throws Exception {
        switch (gesture) {
            case "wheel" -> fx(() -> { scroll(0, 40); return null; });
            case "wheel-burst" -> { for (int i = 0; i < 6; i++) { fx(() -> { scroll(0, 40); return null; }); Thread.sleep(16); } }
            case "trackpad" -> {
                fx(() -> { scrollEvent(ScrollEvent.SCROLL_STARTED, 0, 0); return null; });
                for (int i = 0; i < 6; i++) { fx(() -> { scroll(8, 3); return null; }); Thread.sleep(16); }
                fx(() -> { scrollEvent(ScrollEvent.SCROLL_FINISHED, 0, 0); return null; });
            }
            case "pinch" -> {
                fx(() -> { zoom(ZoomEvent.ZOOM_STARTED, 1); return null; });
                for (int i = 0; i < 6; i++) { fx(() -> { zoom(ZoomEvent.ZOOM, 1.03); return null; }); Thread.sleep(16); }
                fx(() -> { zoom(ZoomEvent.ZOOM_FINISHED, 1); return null; });
            }
            case "drag" -> {
                fx(() -> { mouse(MouseEvent.MOUSE_PRESSED, 0, true); return null; });
                for (int i = 1; i <= 6; i++) { int dx = i * 8; fx(() -> { mouse(MouseEvent.MOUSE_DRAGGED, dx, true); return null; }); Thread.sleep(16); }
                fx(() -> { mouse(MouseEvent.MOUSE_RELEASED, 48, false); return null; });
            }
            default -> throw new IllegalArgumentException("Unknown gesture " + gesture);
        }
    }

    private void scroll(double dx, double dy) { scrollEvent(ScrollEvent.SCROLL, dx, dy); }
    private void scrollEvent(javafx.event.EventType<ScrollEvent> type, double dx, double dy) {
        Event.fireEvent(view, new ScrollEvent(type, view.getWidth() / 2, view.getHeight() / 2, 0, 0,
                false, false, false, false, false, false, dx, dy, dx, dy,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0, ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
    }
    private void zoom(javafx.event.EventType<ZoomEvent> type, double factor) {
        Event.fireEvent(view, new ZoomEvent(type, view.getWidth() / 2, view.getHeight() / 2, 0, 0,
                false, false, false, false, false, false, factor, factor, null));
    }
    private void mouse(javafx.event.EventType<MouseEvent> type, int dx, boolean down) {
        Event.fireEvent(view, new MouseEvent(type, view.getWidth() / 2 + dx, view.getHeight() / 2, 0, 0,
                MouseButton.PRIMARY, 1, false, false, false, false, down, false, false, false, false, true, null));
    }
    private void awaitIdle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        // Includes debounce even when a render has not started yet.
        Thread.sleep(160);
        while (!fx(() -> view.hasCompletedFrame() && view.isLatencyIdle())) {
            if (failure != null) throw new IllegalStateException("Screen probe failed", failure);
            if (System.nanoTime() > deadline) throw new IllegalStateException("Renderer did not settle");
            Thread.sleep(20);
        }
    }
    private static <T> T fx(Callable<T> operation) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { result.complete(operation.call()); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result.get(60, TimeUnit.SECONDS);
    }
}
