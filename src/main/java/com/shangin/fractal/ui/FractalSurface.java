package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.Palette;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.FractalColorizer;
import com.shangin.fractal.render.FractalData;
import com.shangin.fractal.render.RenderProgressBatch;
import com.shangin.fractal.render.RenderRegion;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Affine;
import javafx.stage.Window;

import java.util.List;
import java.util.Objects;

public final class FractalSurface extends Region {

    private final Affine previewTransform = new Affine();
    private final ImageView baseImageView = new ImageView();
    private final ImageView progressiveImageView = new ImageView();
    private final FractalColorizer colorizer = new FractalColorizer();

    private SurfaceBuffer stagingFrame;
    private SurfaceBuffer displayedFrame;
    private FractalData displayedData;
    private Viewport displayedViewport;

    private Runnable renderScaleChangedHandler = () -> {};

    private int renderWidth;
    private int renderHeight;
    private double outputScaleX = 1.0;
    private double outputScaleY = 1.0;

    public FractalSurface() {
        setMinSize(0, 0);
        configureImageViews();
        configureClip();
        configureHiDpi();
        getChildren().addAll(baseImageView, progressiveImageView);
    }

    public void beginProgressiveRender() {
        if (renderWidth < 2 || renderHeight < 2) {
            return;
        }

        if (stagingFrame == null
                || !stagingFrame.matches(
                renderWidth,
                renderHeight
        )) {

            stagingFrame =
                    new SurfaceBuffer(
                            renderWidth,
                            renderHeight
                    );
        }

        stagingFrame.clear();

        progressiveImageView.setImage(
                stagingFrame.image()
        );
    }

    private void configureImageViews() {
        configureImageView(baseImageView);
        configureImageView(progressiveImageView);

        baseImageView
                .getTransforms()
                .add(previewTransform);

        progressiveImageView.setMouseTransparent(true);
    }

    private void configureImageView(
            ImageView imageView
    ) {
        imageView.setManaged(false);
        imageView.setPreserveRatio(false);
        imageView.setSmooth(false);

        imageView.fitWidthProperty()
                .bind(widthProperty());

        imageView.fitHeightProperty()
                .bind(heightProperty());
    }

    public void displayProgress(
            RenderProgressBatch progress,
            ColoringStrategy coloring
    ) {
        if (stagingFrame == null) {
            return;
        }

        FractalData data = progress.data();

        if (data.width() != stagingFrame.width() || data.height() != stagingFrame.height()) {
            return;
        }

        if (progress.regions().isEmpty()) {
            return;
        }

        for (RenderRegion region : progress.regions()) {
            colorizer.colorRegion(
                    data,
                    stagingFrame.intBuffer(),
                    coloring,
                    region
            );
        }

        Rectangle2D dirtyRegion = dirtyRegion(progress.regions());

        stagingFrame.pixelBuffer().updateBuffer(
                pixelBuffer -> dirtyRegion
        );
    }

    private static Rectangle2D dirtyRegion(
            List<RenderRegion> regions
    ) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;

        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (RenderRegion region : regions) {
            minX = Math.min(minX, region.x());

            minY = Math.min(minY, region.y());

            maxX = Math.max(maxX, region.x() + region.width());

            maxY = Math.max(maxY, region.y() + region.height());
        }

        return new Rectangle2D(
                minX,
                minY,
                maxX - minX,
                maxY - minY
        );
    }

    public void completeProgressiveRender(
            FractalData data,
            Viewport viewport
    ) {

        if (stagingFrame == null) {
            return;
        }

        promoteStagingFrame(data, viewport);
    }

    private void promoteStagingFrame(
            FractalData data,
            Viewport viewport
    ) {
        SurfaceBuffer oldDisplayed =
                displayedFrame;

        displayedFrame =
                stagingFrame;

        stagingFrame =
                oldDisplayed;

        displayedData = data;
        displayedViewport = viewport;

        baseImageView.setImage(
                displayedFrame.image()
        );

        resetPreview();

        progressiveImageView.setImage(null);
    }

    public void resizeBuffer(
            int logicalWidth,
            int logicalHeight
    ) {
        int newRenderWidth = Math.max(2, (int) Math.ceil(logicalWidth * outputScaleX));
        int newRenderHeight = Math.max(2, (int) Math.ceil(logicalHeight * outputScaleY));

        if (stagingFrame != null && stagingFrame.matches(newRenderWidth, newRenderHeight)) {
            return;
        }
        renderWidth = newRenderWidth;
        renderHeight = newRenderHeight;
        stagingFrame = new SurfaceBuffer(renderWidth, renderHeight);
    }

    public int renderWidth() {
        return renderWidth;
    }

    public int renderHeight() {
        return renderHeight;
    }

    private void resetPreview() {
        previewTransform.setToIdentity();
    }

    public void setOutputScale(
            double outputScaleX,
            double outputScaleY)
    {
        if (!Double.isFinite(outputScaleX)
                || outputScaleX <= 0.0
                || !Double.isFinite(outputScaleY)
                || outputScaleY <= 0.0) {

            throw new IllegalArgumentException("Output scale must be positive and finite");
        }

        this.outputScaleX = outputScaleX;
        this.outputScaleY = outputScaleY;
    }

    public void showPreview(Viewport targetViewport) {

        progressiveImageView.setImage(null);

        if (displayedViewport == null) {
            return;
        }

        int width = (int) getWidth();
        int height = (int) getHeight();

        if (width < 2 || height < 2) {
            return;
        }

        Viewport source = displayedViewport;

        double scaleX = source.visibleWidth(width, height)
                        / targetViewport.visibleWidth(width, height);

        double scaleY = source.visibleHeight()
                        / targetViewport.visibleHeight();

        double translateX = (source.minReal(width, height)
                        - targetViewport.minReal(width, height))
                        / targetViewport.visibleWidth(width, height)
                        * (width - 1.0);

        double translateY = (targetViewport.maxImaginary()
                        - source.maxImaginary())
                        / targetViewport.visibleHeight()
                        * (height - 1.0);

        previewTransform.setToIdentity();

        previewTransform.setMxx(scaleX);
        previewTransform.setMyy(scaleY);
        previewTransform.setTx(translateX);
        previewTransform.setTy(translateY);
    }

    public void display(
            FractalData data,
            ColoringStrategy coloring,
            Viewport viewport
    ) {
        if (stagingFrame == null) {
            return;
        }

        if (data.width() != renderWidth
                || data.height() != renderHeight) {
            throw new IllegalArgumentException(
                    "Fractal data dimensions do not match render buffer"
            );
        }

        colorizer.color(data, stagingFrame.intBuffer(), coloring);

        stagingFrame.update();

        promoteStagingFrame(data, viewport);
    }

    public void recolor(ColoringStrategy coloring) {
        if (displayedData == null || displayedFrame == null) {
            return;
        }

        colorizer.color(displayedData, displayedFrame.intBuffer(), coloring);

        displayedFrame.update();
    }

    public void setPreviewBackground(Palette palette) {
        int argb = palette.middleColor();

        Color color = toFxColor(argb);

        setBackground(new Background(new BackgroundFill(color, CornerRadii.EMPTY, Insets.EMPTY)));
    }

    private static Color toFxColor(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;

        return Color.rgb(
                red,
                green,
                blue,
                alpha / 255.0
        );
    }

    private void configureClip() {
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
    }

    private void configureHiDpi() {
        sceneProperty().addListener(
                (observable, oldScene, newScene) -> {
                    if (newScene == null) {
                        return;
                    }

                    newScene.windowProperty().addListener(
                            (obs, oldWindow, newWindow) -> {
                                if (newWindow != null) {
                                    configureWindowScale(newWindow);
                                }
                            }
                    );

                    if (newScene.getWindow() != null) {
                        configureWindowScale(
                                newScene.getWindow()
                        );
                    }
                }
        );
    }

    private void configureWindowScale(Window window) {
        setOutputScale(window.getOutputScaleX(), window.getOutputScaleY());

        window.outputScaleXProperty().addListener(
                (obs, oldValue, newValue) -> {
                    setOutputScale(window.getOutputScaleX(), window.getOutputScaleY());
                    renderScaleChangedHandler.run();
                }
        );

        window.outputScaleYProperty().addListener(
                (obs, oldValue, newValue) -> {
                    setOutputScale(window.getOutputScaleX(), window.getOutputScaleY());
                    renderScaleChangedHandler.run();
                }
        );

        renderScaleChangedHandler.run();
    }

    public void setOnOutputScaleChanged(Runnable handler) {
        renderScaleChangedHandler =
                Objects.requireNonNull(handler);
    }
}
