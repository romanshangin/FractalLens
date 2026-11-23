package com.shangin.fractal.ui;

import com.shangin.fractal.app.FractalLauncher;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.scene.IterationSettings;
import com.shangin.fractal.scene.JuliaParameters;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Drives Render > Explore Julia Parameters in a visible window: opening the map, clicks
 * and drags on it at fixed logical sizes and iteration limits. Every published GPU frame
 * carries a unique code in a diagnostic strip; JavaFX Robot capture of that strip is a
 * compositor proxy for presentation, not panel scanout. Input is synthetic JavaFX
 * dispatch: hardware and OS input latency is excluded.
 */
public final class JuliaExplorerLatencyBenchmark {
    private static final String PREFIX = "fractal.juliaLatency.";
    private static final int BITS = 12;
    private static final int STRIP_HEIGHT = 16;
    private static final int STEP_NANOS = 16_000_000;
    /** Alternating trial endpoints in logical map points: c near -0.8+0.156i (dendrite) and -0.12+0.74i (rabbit). */
    private static final Point2D FIRST = new Point2D(135, 83);
    private static final Point2D SECOND = new Point2D(190, 36);
    private final Rectangle[] markers = new Rectangle[BITS];
    private final Map<Integer, InteractionLatency.Event> markerEvents = new HashMap<>();
    private final Set<Integer> observedCodes = new HashSet<>();
    private final List<String> rows = new ArrayList<>();
    private final boolean screen = !Boolean.getBoolean(PREFIX + "noScreen");
    private InteractionLatency.Event pendingPulse;
    private int currentCode;
    private int nextCode;
    private FractalView view;
    private BorderPane root;
    private HBox strip;
    private Stage stage;
    private JuliaExplorer explorer;
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
        FractalLauncher.launch(BenchmarkApplication.class, args);
        if (failure != null) throw new IllegalStateException("Julia explorer latency benchmark failed", failure);
    }

    private void start(Stage stage) {
        this.stage = stage;
        view = new FractalView(FractalPreset.JULIA, PalettePreset.ICE);
        strip = new HBox();
        strip.getChildren().add(new Rectangle(16, STRIP_HEIGHT, Color.MAGENTA));
        for (int bit = 0; bit < BITS; bit++) {
            markers[bit] = new Rectangle(4, STRIP_HEIGHT, Color.BLACK);
            strip.getChildren().add(markers[bit]);
        }
        root = new BorderPane(view);
        // The map window opens near the top-left corner of the main window; keep the strip clear of it.
        root.setBottom(strip);
        int[] first = sizes().getFirst();
        root.setPrefSize(first[0], first[1] + STRIP_HEIGHT);
        Scene scene = new Scene(root);
        scene.addPostLayoutPulseListener(() -> {
            if (recording && pendingPulse != null) view.latency().observed(pendingPulse, "_post_layout");
            pendingPulse = null;
        });
        stage.setTitle("FractalLens — Julia Explorer Latency Benchmark");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.setAlwaysOnTop(true);
        stage.show();
        stage.toFront();
        robot = new Robot();
        timer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (!screen) return;
                try { probe(); }
                catch (Throwable error) { failure = error; timer.stop(); }
            }
        };
        timer.start();
        new Thread(this::run, "julia-explorer-latency-driver").start();
    }

    private static List<int[]> sizes() {
        List<int[]> sizes = new ArrayList<>();
        for (String size : System.getProperty(PREFIX + "sizes", "1200x760,1680x1000").split(",")) {
            String[] parts = size.split("x");
            sizes.add(new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])});
        }
        return sizes;
    }

    private void probe() {
        Point2D point = strip.localToScreen(0, 0);
        if (point == null) return;
        long started = System.nanoTime();
        capture = robot.getScreenCapture(capture, point.getX(), point.getY(), 16 + BITS * 4, STRIP_HEIGHT, true);
        long observed = System.nanoTime();
        Color sentinel = capture.getPixelReader().getColor(8, 8);
        if (!(sentinel.getRed() > .7 && sentinel.getBlue() > .7 && sentinel.getGreen() < .3)) return;
        screenValidated = true;
        int code = readCode();
        screenBlack = code == 0;
        if (!recording) return;
        if (lastProbe != 0) largestProbeGap = Math.max(largestProbeGap, observed - lastProbe);
        lastProbe = observed;
        probeWork += observed - started;
        var event = markerEvents.get(code);
        if (event != null && observedCodes.add(code)) view.latency().observed(event, "_screen_observed");
    }

    private int readCode() {
        int code = 0;
        // Retina capture: sample each 4-point bar at its center.
        for (int bit = 0; bit < BITS; bit++) {
            double brightness = capture.getPixelReader().getColor(18 + bit * 4, 8).getBrightness();
            if (brightness > .8) code |= 1 << bit;
            else if (brightness >= .2) return -1;
        }
        return code;
    }

    private void published(InteractionLatency.Event event) {
        if (!event.stage().equals("approximate_publish")) return;
        if (++nextCode >= 1 << BITS) throw new IllegalStateException("Too many frame publications in one trial");
        int code = nextCode;
        markerEvents.put(code, event);
        currentCode = code;
        for (int bit = 0; bit < BITS; bit++) markers[bit].setFill((code & (1 << bit)) == 0 ? Color.BLACK : Color.WHITE);
        // Only the last code before a pulse can be observed; superseded frames stay unobserved.
        pendingPulse = event;
    }

    private void run() {
        Path output = Path.of(System.getProperty(PREFIX + "output", "target/julia-explorer-latency.csv"));
        try {
            int samples = Integer.getInteger(PREFIX + "samples", 10);
            int warmups = Integer.getInteger(PREFIX + "warmup", 2);
            String cold = System.getProperty(PREFIX + "cold", "open");
            if (samples < 0 || warmups < 0) throw new IllegalArgumentException("Invalid sample counts");
            if (!cold.equals("open") && !cold.equals("drag")) throw new IllegalArgumentException("Unknown cold mode " + cold);
            List<Integer> iterations = new ArrayList<>();
            for (String value : System.getProperty(PREFIX + "iterations", "300,1000").split(",")) {
                iterations.add(Integer.parseInt(value));
            }
            List<String> gestures = List.of(System.getProperty(PREFIX + "gestures", "click,drag").split(","));
            for (String gesture : gestures) {
                if (!gesture.equals("click") && !gesture.equals("drag")) throw new IllegalArgumentException("Unknown gesture " + gesture);
            }
            awaitIdle();
            Thread.sleep(300);
            if (screen && !fx(() -> screenValidated)) {
                throw new IllegalStateException("Screen marker not readable. Check screen-recording permission and window visibility; no screen latency can be reported.");
            }
            rows.add("# Java=" + System.getProperty("java.version") + "; JavaFX=" + System.getProperty("javafx.runtime.version")
                    + "; os=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                    + "; arch=" + System.getProperty("os.arch")
                    + "; requestedPipeline=" + System.getProperty("prism.order", "default")
                    + "; screenProbe=" + screen + "; cold=" + cold
                    + "; time=" + java.time.Instant.now());
            rows.add("size,iterations,gesture,phase,iteration,input,render,stage,nanos,duration_nanos");
            boolean firstOpen = true;
            for (int[] size : sizes()) {
                for (int limit : iterations) {
                    String config = size[0] + "x" + size[1] + "," + limit + ",";
                    fx(() -> {
                        if (explorer != null) explorer.close();
                        explorer = null;
                        root.setPrefSize(size[0], size[1] + STRIP_HEIGHT);
                        stage.sizeToScene();
                        return null;
                    });
                    awaitIdle();
                    fx(() -> { view.setIterations(new IterationSettings(limit, IterationSettings.DEFAULT_ITERATIONS_PER_ZOOM_LEVEL)); return null; });
                    awaitIdle();
                    fx(() -> { view.setJuliaParameters(JuliaExplorer.point(FIRST.getX(), FIRST.getY())); return null; });
                    awaitIdle();
                    if (fx(() -> view.getWidth() != size[0] || view.getHeight() != size[1])) {
                        throw new IllegalStateException("Window did not reach " + size[0] + "x" + size[1]
                                + "; it is " + fx(() -> view.getWidth() + "x" + view.getHeight()));
                    }
                    rows.add("# config=" + size[0] + "x" + size[1] + "/" + limit + "; " + fx(() -> "logical="
                            + view.getWidth() + "x" + view.getHeight()
                            + "; outputScale=" + stage.getOutputScaleX() + "x" + stage.getOutputScaleY()
                            + "; physical=" + Math.round(view.getWidth() * stage.getOutputScaleX()) + "x"
                            + Math.round(view.getHeight() * stage.getOutputScaleY())
                            + "; iterations=" + view.sceneSnapshot().iterations()
                            + "; first=" + JuliaExplorer.point(FIRST.getX(), FIRST.getY())
                            + "; second=" + JuliaExplorer.point(SECOND.getX(), SECOND.getY())));
                    boolean coldDrag = firstOpen && cold.equals("drag");
                    String openGesture = coldDrag ? "open-drag" : "open";
                    trial(config + openGesture + "," + (firstOpen ? "cold" : "open") + ",0,",
                            () -> open(coldDrag), coldDrag ? SECOND : FIRST);
                    firstOpen = false;
                    Point2D current = coldDrag ? SECOND : FIRST;
                    for (int iteration = -warmups; iteration < samples; iteration++) {
                        for (String gesture : gestures) {
                            Point2D from = current;
                            Point2D to = from.equals(FIRST) ? SECOND : FIRST;
                            String prefix = config + gesture + "," + (iteration < 0 ? "warmup" : "sample") + "," + iteration + ",";
                            trial(prefix, gesture.equals("drag") ? () -> drag(from, to, false) : () -> click(to), to);
                            current = to;
                        }
                    }
                    System.out.println(size[0] + "x" + size[1] + " " + limit + " complete");
                }
            }
            fx(() -> { if (explorer != null) explorer.close(); explorer = null; return null; });
        } catch (Throwable error) {
            failure = error;
            error.printStackTrace();
            rows.add("# FAILED: " + error);
            try {
                fx(() -> { recording = false; if (view.latency().isEnabled()) view.latency().stop(); return null; });
            } catch (Exception diagnosticError) { diagnosticError.printStackTrace(); }
        } finally {
            try {
                if (output.toAbsolutePath().getParent() != null) Files.createDirectories(output.toAbsolutePath().getParent());
                Files.write(output, rows);
                System.out.println("Saved " + output);
            } catch (Exception error) { failure = error; error.printStackTrace(); }
            Platform.runLater(() -> { timer.stop(); if (explorer != null) explorer.close(); view.close(); stage.close(); Platform.exit(); });
        }
    }

    private interface Gesture { void perform() throws Exception; }

    private void trial(String prefix, Gesture gesture, Point2D target) throws Exception {
        fx(() -> {
            for (Rectangle marker : markers) marker.setFill(Color.BLACK);
            markerEvents.clear(); observedCodes.clear(); currentCode = nextCode = 0;
            pendingPulse = null; screenBlack = false;
            lastProbe = largestProbeGap = probeWork = 0;
            return null;
        });
        Thread.sleep(120);
        if (screen) {
            long resetDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!fx(() -> screenBlack)) {
                if (System.nanoTime() > resetDeadline) throw new IllegalStateException("Black marker reset was not observed; refusing stale screen timestamps");
                Thread.sleep(20);
            }
        }
        fx(() -> { view.latency().arm(this::published); recording = true; return null; });
        gesture.perform();
        JuliaParameters expected = JuliaExplorer.point(target.getX(), target.getY());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        Thread.sleep(50);
        while (!fx(() -> explorer.isShowing() && !explorer.isEditing() && view.isLatencyIdle()
                && view.showsApproximateJuliaPoint(expected))) {
            if (failure != null) throw new IllegalStateException("Screen probe failed", failure);
            if (System.nanoTime() > deadline) throw new IllegalStateException("Explorer did not settle at the target");
            Thread.sleep(10);
        }
        if (screen) {
            long screenDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!fx(() -> currentCode != 0 && observedCodes.contains(currentCode))) {
                if (System.nanoTime() > screenDeadline) throw new IllegalStateException("Final frame marker did not reach the screen");
                Thread.sleep(10);
            }
        } else Thread.sleep(50);
        List<InteractionLatency.Event> events = fx(() -> {
            view.latency().boundary("trial_end");
            recording = false;
            return view.latency().stop();
        });
        if (!fx(() -> view.sceneSnapshot().juliaParameters().equals(expected))) {
            throw new IllegalStateException("Committed Julia constant differs from the target");
        }
        boolean opening = prefix.contains(",open");
        if (!opening && events.stream().noneMatch(e -> e.stage().equals("map_committed"))) {
            throw new IllegalStateException("Gesture did not commit");
        }
        for (var event : events) {
            rows.add(prefix + event.input() + "," + event.render() + "," + event.stage() + "," + event.nanos() + "," + event.durationNanos());
        }
        rows.add(prefix + "0,0,screen_probe_max_gap,0," + largestProbeGap);
        rows.add(prefix + "0,0,screen_probe_total_work,0," + probeWork);
    }

    private void open(boolean thenDrag) throws Exception {
        fx(() -> {
            view.latency().boundary("map_open");
            explorer = new JuliaExplorer(stage, view);
            explorer.show();
            view.startJuliaExplorerRender();
            if (thenDrag) mouse(MouseEvent.MOUSE_PRESSED, FIRST, true);
            return null;
        });
        if (thenDrag) drag(FIRST, SECOND, true);
    }

    /** Press, 48 drag events at 16 ms (the first past the click threshold), a 250 ms hold and release. */
    private void drag(Point2D from, Point2D to, boolean pressed) throws Exception {
        if (!pressed) fx(() -> { mouse(MouseEvent.MOUSE_PRESSED, from, true); return null; });
        Point2D direction = to.subtract(from).normalize();
        Point2D firstStep = from.add(direction.multiply(5));
        int steps = 48;
        long next = System.nanoTime();
        for (int step = 0; step < steps; step++) {
            Point2D point = step == 0 ? firstStep
                    : firstStep.add(to.subtract(firstStep).multiply((double) step / (steps - 1)));
            next += STEP_NANOS;
            sleepUntil(next);
            fx(() -> { mouse(MouseEvent.MOUSE_DRAGGED, point, true); return null; });
        }
        Thread.sleep(250);
        fx(() -> { mouse(MouseEvent.MOUSE_RELEASED, to, false); return null; });
    }

    private void click(Point2D to) throws Exception {
        fx(() -> { mouse(MouseEvent.MOUSE_PRESSED, to, true); return null; });
        Thread.sleep(80);
        fx(() -> { mouse(MouseEvent.MOUSE_RELEASED, to, false); return null; });
    }

    private static void sleepUntil(long deadline) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) Thread.sleep(remaining / 1_000_000, (int) (remaining % 1_000_000));
    }

    private void mouse(javafx.event.EventType<MouseEvent> type, Point2D point, boolean down) {
        Node map = explorer.getScene().getRoot();
        Point2D screenPoint = map.localToScreen(point);
        double screenX = screenPoint == null ? 0 : screenPoint.getX();
        double screenY = screenPoint == null ? 0 : screenPoint.getY();
        Event.fireEvent(map, new MouseEvent(type, point.getX(), point.getY(), screenX, screenY,
                MouseButton.PRIMARY, 1, false, false, false, false, down, false, false, false, false, true, null));
    }

    private void awaitIdle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        // Includes resize and interaction debounce even when a render has not started yet.
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

    /** JavaFX lifecycle adapter; the outer main class must remain plain Java. */
    public static final class BenchmarkApplication extends Application {
        private final JuliaExplorerLatencyBenchmark benchmark = new JuliaExplorerLatencyBenchmark();

        @Override
        public void start(Stage stage) {
            benchmark.start(stage);
        }
    }
}
