package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.config.JuliaClickSpeed;
import com.shangin.fractal.render.FractalColorizer;
import com.shangin.fractal.render.MandelbrotBoundaryDistance;
import com.shangin.fractal.render.SamplePlane;
import com.shangin.fractal.render.ScenePreviewRenderer;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.ColoringSettings;
import com.shangin.fractal.scene.IterationSettings;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.render.RenderGrid;
import com.shangin.fractal.scene.JuliaParameters;
import javafx.animation.AnimationTimer;
import javafx.application.ColorScheme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/** A floating parameter map. All gesture and publication decisions run on the FX thread. */
final class JuliaExplorer extends Stage {
    static final String TITLE = "Explore Julia Parameters…";
    static final int WIDTH = 280;
    static final int HEIGHT = 192;
    private static final RenderGrid GRID = RenderGrid.from(FractalPreset.MANDELBROT.defaultViewport(), WIDTH, HEIGHT);
    private final FractalView view;
    private final Pane map;
    private final Circle marker = new Circle(5, Color.ORANGERED);
    private final Label realCoordinate = new Label();
    private final Label imaginaryCoordinate = new Label();
    private final DecimalFormat coordinateFormat = new DecimalFormat("0.000", DecimalFormatSymbols.getInstance(Locale.ROOT));
    // One image for the window's lifetime: thumbnail colors are written into it in place.
    private final WritableImage thumbnail = new WritableImage(WIDTH, HEIGHT);
    private final FractalColorizer colorizer = new FractalColorizer();
    private SamplePlane thumbnailSamples;
    private MandelbrotBoundaryDistance boundary;
    private ColoringSettings thumbnailColoring;
    private JuliaParameters displayed;
    private JuliaParameters submitted;
    private JuliaParameters desired;
    private JuliaParameters pressedPoint;
    private final JuliaFramePacing pacing = new JuliaFramePacing();
    private static final double DRAG_THRESHOLD = 4;
    private static final double FRAME_FRACTION = 0.05;
    private long previousFrameTime;
    private double unitsPerPoint;
    private double pressX;
    private double pressY;
    private boolean dragged;
    private boolean replacePrevious;
    private boolean editing;
    private boolean finish;
    private JuliaParameters committed;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override public void handle(long now) {
            advanceFrame(now);
        }
    };

    void advanceFrame(long now) {
        boolean idle = view.isLatencyIdle();
        if (!idle && (!replacePrevious || view.hasPendingMainViewRender())) return;
        if (idle && !view.showsExplorerPoint(submitted)) {
            view.renderExplorerPoint(submitted);
            return;
        }
        if (desired.equals(submitted)) {
            if (!idle) return;
            if (finish) commit();
            else {
                previousFrameTime = 0;
                timer.stop();
            }
            return;
        }
        JuliaParameters next = dragged ? nextFramePoint(submitted, desired, unitsPerPoint) : nextClickPoint(now, idle);
        if (next.equals(submitted)) return;
        replacePrevious = false;
        view.renderExplorerPoint(next);
        submitted = next;
    }

    JuliaExplorer(Stage owner, FractalView view) {
        this(owner, view, JuliaClickSpeed.DEFAULT, speed -> {});
    }

    JuliaExplorer(Stage owner, FractalView view, JuliaClickSpeed clickSpeed,
                  Consumer<JuliaClickSpeed> onClickSpeedChanged) {
        this.view = view;
        FractalScene initialScene = view.sceneSnapshot();
        committed = submitted = desired = initialScene.juliaParameters();
        thumbnailColoring = thumbnailColoring(initialScene.coloring());
        initOwner(owner);
        setResizable(false);
        setTitle("Julia Parameter Map");
        fillThumbnail(thumbnailColoring.paletteStops().getFirst().color());
        var imageView = new ImageView(thumbnail);
        marker.setStroke(Color.WHITE);
        marker.setMouseTransparent(true);
        map = new Pane(imageView, marker);
        map.setPrefSize(WIDTH, HEIGHT);
        map.setPickOnBounds(true);
        map.setAccessibleText("Mandelbrot parameter map");
        Tooltip.install(map, new Tooltip("Click to move, drag to explore, or Shift-drag for fine adjustment."));
        realCoordinate.setId("julia-coordinate-real");
        imaginaryCoordinate.setId("julia-coordinate-imaginary");
        double coordinateWidth = (WIDTH - 16) / 2.0;
        realCoordinate.setPrefWidth(coordinateWidth);
        imaginaryCoordinate.setPrefWidth(coordinateWidth);
        var coordinates = new HBox(realCoordinate, imaginaryCoordinate);
        coordinates.setPadding(new Insets(6, 8, 6, 8));
        showPublishedPoint(Objects.requireNonNullElse(view.displayedJuliaPoint(), submitted));
        var speedChoice = new ChoiceBox<JuliaClickSpeed>();
        speedChoice.setId("julia-click-speed");
        speedChoice.getItems().setAll(JuliaClickSpeed.values());
        speedChoice.setValue(clickSpeed);
        speedChoice.setOnAction(event -> onClickSpeedChanged.accept(speedChoice.getValue()));
        var speedLabel = new Label("Click speed");
        speedLabel.setLabelFor(speedChoice);
        speedChoice.setAccessibleText(speedLabel.getText());
        var controls = new HBox(8, speedLabel, speedChoice);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(6, 8, 6, 8));
        var root = new VBox(coordinates, map, controls);
        var scene = new Scene(root);
        root.styleProperty().bind(scene.getPreferences().colorSchemeProperty().map(scheme ->
                scheme == ColorScheme.DARK ? "-fx-base: #383838;" : ""));
        setScene(scene);
        setX(owner.getX() + 20);
        setY(owner.getY() + 60);
        map.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            view.latency().input("map_press");
            timer.stop();
            previousFrameTime = 0;
            pacing.reset(speedChoice.getValue());
            // Keep even a user-entered constant far outside the map reachable.
            unitsPerPoint = Math.max(GRID.realStep(),
                    Math.max(Math.abs(displayed.real()), Math.abs(displayed.imaginary())) / WIDTH);
            finish = false;
            pressedPoint = displayed;
            desired = displayed;
            replacePrevious = !editing;
            pressX = e.getX();
            pressY = e.getY();
            dragged = false;
        });
        map.setOnMouseDragged(e -> {
            if (!e.isPrimaryButtonDown() || pressedPoint == null) return;
            view.latency().input("map_drag");
            if (!dragged && Math.hypot(e.getX() - pressX, e.getY() - pressY) < DRAG_THRESHOLD) return;
            dragged = true;
            editing = true;
            desired = dragPoint(e.getX(), e.getY(), e.isShiftDown());
            timer.start();
        });
        map.setOnMouseReleased(e -> {
            if (e.getButton() != MouseButton.PRIMARY || pressedPoint == null) return;
            view.latency().input("map_release");
            editing = true;
            desired = dragged ? dragPoint(e.getX(), e.getY(), e.isShiftDown()) : point(e.getX(), e.getY());
            finish = true;
            pressedPoint = null;
            timer.start();
        });
        getScene().setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) close();
        });
        Thread thumbnailLoader = Thread.ofVirtual().unstarted(this::loadThumbnail);
        setOnShown(e -> {
            view.setJuliaExplorer(this);
            thumbnailLoader.start();
        });
        setOnHidden(e -> {
            thumbnailLoader.interrupt();
            timer.stop();
            view.setJuliaExplorer(null);
            if (editing && owner.isShowing()) {
                desired = displayed;
                view.keepExplorerPointOnClose(desired);
                commit();
            }
        });
    }

    Pane map() {
        return map;
    }

    /** Follows a main-scene change: its Julia constant and the palette used for the map. */
    void syncScene(FractalScene scene) {
        if (!committed.equals(scene.juliaParameters())) resetToParameters(scene.juliaParameters());
        ColoringSettings coloring = thumbnailColoring(scene.coloring());
        if (!coloring.equals(thumbnailColoring)) {
            thumbnailColoring = coloring;
            colorThumbnail();
        }
    }

    // The thumbnail stops at 80 iterations, so it stretches the scene's color scale to show
    // the whole palette near the set's boundary. Any valid scene scale stays finite.
    private static final double THUMBNAIL_SCALE_FACTOR = 8;

    /**
     * The main scene's palette, color scale and offset, adapted to the thumbnail's iteration
     * limit, without frame-dependent (histogram) or orbit-trap coloring.
     */
    private static ColoringSettings thumbnailColoring(ColoringSettings scene) {
        return new ColoringSettings(scene.palette(), scene.paletteStops(),
                Math.min(scene.colorScale() * THUMBNAIL_SCALE_FACTOR, Double.MAX_VALUE),
                scene.offset(), false, OrbitTrap.NONE);
    }

    ColoringSettings thumbnailColoring() {
        return thumbnailColoring;
    }

    boolean hasThumbnail() {
        return thumbnailSamples != null;
    }

    int thumbnailArgb(int x, int y) {
        return thumbnail.getPixelReader().getArgb(x, y);
    }

    void resetToParameters(JuliaParameters parameters) {
        timer.stop();
        editing = finish = false;
        pressedPoint = null;
        committed = submitted = desired = parameters;
    }

    void closeAfterRenderFailure() {
        if (editing && view.showsApproximateJuliaPoint(displayed)) {
            desired = displayed;
            commit();
        }
        closeWithoutCommit();
    }

    boolean isEditing() {
        return editing;
    }

    JuliaParameters closeForMainViewRender() {
        JuliaParameters point = editing ? displayed : committed;
        closeWithoutCommit();
        return point;
    }

    JuliaParameters prepareForMainViewRender() {
        submitted = editing ? displayed : committed;
        previousFrameTime = 0;
        replacePrevious = false;
        pacing.reset();
        return submitted;
    }

    void closeWithoutCommit() {
        editing = false;
        close();
    }

    private static final int BOUNDARY_ITERATIONS = 1_000;

    private void loadThumbnail() {
        var scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                .withIterations(new IterationSettings(80, 0));
        try (var renderer = new ScenePreviewRenderer()) {
            Thread worker = Thread.currentThread();
            SamplePlane samples = renderer.renderSamples(scene, WIDTH, HEIGHT, worker::isInterrupted);
            if (samples == null) return;
            // The boundary needs more iterations than the colors: slowly escaping points near
            // the set would otherwise widen its interior.
            SamplePlane boundarySamples = renderer.renderSamples(
                    scene.withIterations(new IterationSettings(BOUNDARY_ITERATIONS, 0)), WIDTH, HEIGHT, worker::isInterrupted);
            if (boundarySamples == null) return;
            var field = MandelbrotBoundaryDistance.of(boundarySamples, GRID, JuliaFramePacing.BOUNDARY_RANGE);
            javafx.application.Platform.runLater(() -> {
                if (!isShowing()) return;
                thumbnailSamples = samples;
                boundary = field;
                colorThumbnail();
            });
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.CancellationException cancelled) {
            if (!Thread.currentThread().isInterrupted()) throw cancelled;
        }
    }

    /**
     * Distance from the Mandelbrot set's boundary in map points, whatever the gesture's scale;
     * unknown, so not limiting, until the thumbnail has loaded.
     */
    double boundaryDistance(JuliaParameters c) {
        if (boundary == null) return Double.POSITIVE_INFINITY;
        return boundary.pixels(c.real(), c.imaginary());
    }

    private void colorThumbnail() {
        if (thumbnailSamples == null) return;
        int[] pixels = new int[WIDTH * HEIGHT];
        colorizer.color(thumbnailSamples, java.nio.IntBuffer.wrap(pixels), thumbnailColoring.createStrategy(thumbnailSamples));
        thumbnail.getPixelWriter().setPixels(0, 0, WIDTH, HEIGHT, PixelFormat.getIntArgbInstance(), pixels, 0, WIDTH);
    }

    private void fillThumbnail(int argb) {
        int[] pixels = new int[WIDTH * HEIGHT];
        java.util.Arrays.fill(pixels, 0xff000000 | argb);
        thumbnail.getPixelWriter().setPixels(0, 0, WIDTH, HEIGHT, PixelFormat.getIntArgbInstance(), pixels, 0, WIDTH);
    }

    private void commit() {
        timer.stop();
        editing = finish = false;
        committed = submitted = desired;
        view.latency().boundary("map_committed");
        view.commitExplorerPoint(desired);
    }

    private JuliaParameters dragPoint(double x, double y, boolean fine) {
        if (!fine) return point(x, y);
        return point(mapX(pressedPoint) + (x - pressX) * 0.1, mapY(pressedPoint) + (y - pressY) * 0.1);
    }

    static JuliaParameters point(double x, double y) {
        return new JuliaParameters(GRID.originReal() + GRID.realStep() * Math.clamp(x, 0, WIDTH - 1),
                GRID.originImaginary() - GRID.imaginaryStep() * Math.clamp(y, 0, HEIGHT - 1));
    }

    private JuliaParameters nextClickPoint(long now, boolean idle) {
        if (idle) view.observeExplorerFrame(pacing);
        if (previousFrameTime == 0) {
            previousFrameTime = now;
            return submitted;
        }
        long elapsed = now - previousFrameTime;
        previousFrameTime = now;
        double distance = distanceInMapPoints(submitted, desired, unitsPerPoint);
        double advance = pacing.advance(distance, elapsed, boundaryDistance(submitted));
        if (advance == 0 && distance > 0) return submitted;
        return advance >= distance ? desired : interpolate(submitted, desired, advance / distance);
    }

    static JuliaParameters nextFramePoint(JuliaParameters start, JuliaParameters target, double unitsPerPoint) {
        // Finish at the exact target once the remaining distance is subpixel on the map.
        return distanceInMapPoints(start, target, unitsPerPoint) <= JuliaFramePacing.SNAP_DISTANCE
                ? target : interpolate(start, target, FRAME_FRACTION);
    }

    private static double distanceInMapPoints(JuliaParameters start, JuliaParameters target, double unitsPerPoint) {
        return Math.hypot((target.real() - start.real()) / unitsPerPoint,
                (target.imaginary() - start.imaginary()) / unitsPerPoint);
    }

    static JuliaParameters interpolate(JuliaParameters start, JuliaParameters end, double t) {
        if (t >= 1) return end;
        return new JuliaParameters(start.real() * (1 - t) + end.real() * t,
                start.imaginary() * (1 - t) + end.imaginary() * t);
    }

    private static double mapX(JuliaParameters c) { return (c.real() - GRID.originReal()) / GRID.realStep(); }
    private static double mapY(JuliaParameters c) { return (GRID.originImaginary() - c.imaginary()) / GRID.imaginaryStep(); }

    void showPublishedPoint(JuliaParameters c) {
        if (!c.equals(displayed)) {
            realCoordinate.setText("Re " + formatCoordinate(c.real()));
            imaginaryCoordinate.setText("Im " + formatCoordinate(c.imaginary()));
            displayed = c;
        }
        double x = mapX(c), y = mapY(c);
        marker.setVisible(x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT);
        if (marker.isVisible()) {
            marker.setCenterX(x);
            marker.setCenterY(y);
        }
    }

    private String formatCoordinate(double value) {
        double magnitude = Math.abs(value);
        // Keep user-entered off-map constants readable in the fixed-width row.
        if (magnitude >= 1_000_000) return String.format(Locale.ROOT, "%.3e", value);
        return coordinateFormat.format(magnitude < 0.0005 ? 0 : value);
    }
}
