package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.render.ScenePreviewRenderer;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.ColoringSettings;
import com.shangin.fractal.scene.IterationSettings;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.render.RenderGrid;
import com.shangin.fractal.scene.JuliaParameters;
import javafx.animation.AnimationTimer;
import javafx.scene.Scene;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

/** A floating parameter map. All gesture and publication decisions run on the FX thread. */
final class JuliaExplorer extends Stage {
    static final String TITLE = "Explore Julia Parameters…";
    static final int WIDTH = 280;
    static final int HEIGHT = 192;
    private static final RenderGrid GRID = RenderGrid.from(FractalPreset.MANDELBROT.defaultViewport(), WIDTH, HEIGHT);
    private final FractalView view;
    private final Circle marker = new Circle(5, Color.ORANGERED);
    private JuliaParameters displayed;
    private JuliaParameters submitted;
    private JuliaParameters desired;
    private JuliaParameters pressedPoint;
    private final JuliaFramePacing pacing = new JuliaFramePacing();
    private static final double DRAG_THRESHOLD = 4;
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
            boolean idle = view.isLatencyIdle();
            if (!idle && (!replacePrevious || view.hasPendingMainViewRender())) return;
            if (idle) {
                if (!view.showsExplorerPoint(submitted)) {
                    view.renderExplorerPoint(submitted);
                    return;
                }
                placeMarker(submitted);
            }
            if (desired.equals(submitted)) {
                if (!idle) return;
                if (finish) commit();
                else {
                    previousFrameTime = 0;
                    stop();
                }
                return;
            }
            if (idle) view.observeExplorerFrame(pacing);
            if (previousFrameTime == 0) {
                previousFrameTime = now;
                return;
            }
            long elapsed = now - previousFrameTime;
            previousFrameTime = now;
            double distance = Math.hypot((desired.real() - submitted.real()) / unitsPerPoint,
                    (desired.imaginary() - submitted.imaginary()) / unitsPerPoint);
            double advance = pacing.advance(distance, elapsed);
            if (advance == 0 && distance > 0) return;
            JuliaParameters next = advance >= distance ? desired : interpolate(submitted, desired, advance / distance);
            replacePrevious = false;
            view.renderExplorerPoint(next);
            submitted = next;
        }
    };

    JuliaExplorer(Stage owner, FractalView view) {
        this.view = view;
        committed = submitted = desired = view.sceneSnapshot().juliaParameters();
        initOwner(owner);
        setResizable(false);
        setTitle("Julia Parameter Map");
        var imageView = new ImageView();
        marker.setStroke(Color.WHITE);
        marker.setMouseTransparent(true);
        var map = new Pane(imageView, marker);
        map.setPrefSize(WIDTH, HEIGHT);
        map.setPickOnBounds(true);
        map.setAccessibleText("Mandelbrot parameter map");
        Tooltip.install(map, new Tooltip("Click to move, drag to explore, or Shift-drag for fine adjustment."));
        placeMarker(submitted);
        setScene(new Scene(map));
        setX(owner.getX() + 20);
        setY(owner.getY() + 60);
        map.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            timer.stop();
            previousFrameTime = 0;
            pacing.reset();
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
            if (!dragged && Math.hypot(e.getX() - pressX, e.getY() - pressY) < DRAG_THRESHOLD) return;
            dragged = true;
            editing = true;
            desired = dragPoint(e.getX(), e.getY(), e.isShiftDown());
            timer.start();
        });
        map.setOnMouseReleased(e -> {
            if (e.getButton() != MouseButton.PRIMARY || pressedPoint == null) return;
            editing = true;
            desired = dragged ? dragPoint(e.getX(), e.getY(), e.isShiftDown()) : point(e.getX(), e.getY());
            finish = true;
            pressedPoint = null;
            timer.start();
        });
        getScene().setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) close();
        });
        Thread thumbnail = Thread.ofVirtual().unstarted(() -> loadThumbnail(imageView));
        setOnShown(e -> {
            view.setJuliaExplorer(this);
            thumbnail.start();
        });
        setOnHidden(e -> {
            thumbnail.interrupt();
            timer.stop();
            view.setJuliaExplorer(null);
            if (editing && owner.isShowing()) {
                desired = visiblePoint();
                view.keepExplorerPointOnClose(desired);
                commit();
            }
        });
    }

    void syncParameters(JuliaParameters parameters) {
        if (!committed.equals(parameters)) resetToParameters(parameters);
    }

    void resetToParameters(JuliaParameters parameters) {
        timer.stop();
        editing = finish = false;
        pressedPoint = null;
        committed = submitted = desired = parameters;
        placeMarker(parameters);
    }

    void closeAfterRenderFailure() {
        JuliaParameters visible = visiblePoint();
        if (editing && view.showsApproximateJuliaPoint(visible)) {
            desired = visible;
            commit();
        }
        closeWithoutCommit();
    }

    boolean isEditing() {
        return editing;
    }

    JuliaParameters closeForMainViewRender() {
        JuliaParameters point = editing ? visiblePoint() : committed;
        closeWithoutCommit();
        return point;
    }

    JuliaParameters prepareForMainViewRender() {
        submitted = visiblePoint();
        previousFrameTime = 0;
        replacePrevious = false;
        pacing.reset();
        placeMarker(submitted);
        return submitted;
    }

    private JuliaParameters visiblePoint() {
        return view.showsApproximateJuliaPoint(submitted) ? submitted : displayed;
    }

    void closeWithoutCommit() {
        editing = false;
        close();
    }

    private void loadThumbnail(ImageView imageView) {
        var scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                .withIterations(new IterationSettings(80, 0))
                .withColoring(new ColoringSettings(PalettePreset.ICE, 0.06, 0.25));
        try (var renderer = new ScenePreviewRenderer()) {
            Thread worker = Thread.currentThread();
            int[] pixels = renderer.render(scene, WIDTH, HEIGHT, worker::isInterrupted);
            if (pixels == null) return;
            javafx.application.Platform.runLater(() -> {
                if (!isShowing()) return;
                var image = new WritableImage(WIDTH, HEIGHT);
                image.getPixelWriter().setPixels(0, 0, WIDTH, HEIGHT,
                        PixelFormat.getIntArgbInstance(), pixels, 0, WIDTH);
                imageView.setImage(image);
            });
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.CancellationException cancelled) {
            if (!Thread.currentThread().isInterrupted()) throw cancelled;
        }
    }

    private void commit() {
        timer.stop();
        editing = finish = false;
        committed = submitted = desired;
        view.commitExplorerPoint(desired);
        placeMarker(desired);
    }

    private JuliaParameters dragPoint(double x, double y, boolean fine) {
        if (!fine) return point(x, y);
        return point(mapX(pressedPoint) + (x - pressX) * 0.1, mapY(pressedPoint) + (y - pressY) * 0.1);
    }

    static JuliaParameters point(double x, double y) {
        return new JuliaParameters(GRID.originReal() + GRID.realStep() * Math.clamp(x, 0, WIDTH - 1),
                GRID.originImaginary() - GRID.imaginaryStep() * Math.clamp(y, 0, HEIGHT - 1));
    }

    static JuliaParameters interpolate(JuliaParameters start, JuliaParameters end, double t) {
        if (t >= 1) return end;
        return new JuliaParameters(start.real() * (1 - t) + end.real() * t,
                start.imaginary() * (1 - t) + end.imaginary() * t);
    }

    private static double mapX(JuliaParameters c) { return (c.real() - GRID.originReal()) / GRID.realStep(); }
    private static double mapY(JuliaParameters c) { return (GRID.originImaginary() - c.imaginary()) / GRID.imaginaryStep(); }

    private void placeMarker(JuliaParameters c) {
        displayed = c;
        double x = mapX(c), y = mapY(c);
        marker.setVisible(x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT);
        if (marker.isVisible()) {
            marker.setCenterX(x);
            marker.setCenterY(y);
        }
    }
}
