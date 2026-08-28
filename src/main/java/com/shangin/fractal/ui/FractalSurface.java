package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.Palette;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
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

/**
 * JavaFX surface that owns display buffers, applies interaction previews, and
 * publishes progressively colored regions to a PixelBuffer-backed image.
 */
public final class FractalSurface extends Region {

    private final Affine previewTransform = new Affine();
    private final Affine progressivePreviewTransform = new Affine();
    private final ImageView baseImageView = new ImageView();
    private final ImageView progressiveImageView = new ImageView();
    private final FractalColorizer colorizer = new FractalColorizer();

    private SurfaceBuffer stagingFrame;
    private SurfaceBuffer displayedFrame;
    private RenderFrame stagingRenderFrame;
    private RenderFrame displayedRenderFrame;
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

    /** Prepares the staging buffer for a new progressive frame. */
    public void beginProgressiveRender(RenderFrame renderFrame) {
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

        if (stagingRenderFrame != renderFrame) {
            stagingFrame.clear();
        }

        stagingRenderFrame = renderFrame;
        progressivePreviewTransform.setToIdentity();

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

        progressiveImageView
                .getTransforms()
                .add(progressivePreviewTransform);

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

    /** Colors and publishes regions completed by the background renderer. */
    public void displayProgress(
            RenderProgressBatch progress,
            ColoringStrategy coloring
    ) {
        if (stagingFrame == null) {
            return;
        }

        RenderFrame renderFrame = progress.frame();

        if (stagingRenderFrame != renderFrame) {
            return;
        }

        FractalData data = renderFrame.fractalData();

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
            RenderFrame renderFrame,
            Viewport viewport
    ) {

        if (stagingFrame == null) {
            return;
        }

        promoteStagingFrame(renderFrame, viewport);
    }

    private void promoteStagingFrame(
            RenderFrame renderFrame,
            Viewport viewport
    ) {
        SurfaceBuffer oldDisplayed =
                displayedFrame;

        displayedFrame =
                stagingFrame;

        stagingFrame =
                oldDisplayed;

        displayedRenderFrame = renderFrame;
        displayedViewport = viewport;
        stagingRenderFrame = null;

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
        stagingRenderFrame = null;
    }

    public int renderWidth() {
        return renderWidth;
    }

    public int renderHeight() {
        return renderHeight;
    }

    private void resetPreview() {
        previewTransform.setToIdentity();
        progressivePreviewTransform.setToIdentity();
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

    /** Transforms the last completed image as an immediate pan/zoom preview. */
    public void showPreview(Viewport targetViewport) {

        if (displayedViewport == null) {
            return;
        }

        int width = (int) getWidth();
        int height = (int) getHeight();

        if (width < 2 || height < 2) {
            return;
        }

        applyPreviewTransform(
                previewTransform,
                displayedViewport,
                targetViewport,
                width,
                height
        );

        if (stagingRenderFrame != null) {
            applyPreviewTransform(
                    progressivePreviewTransform,
                    stagingRenderFrame.request().viewport(),
                    targetViewport,
                    width,
                    height
            );
        }
    }

    private static void applyPreviewTransform(
            Affine transform,
            Viewport source,
            Viewport targetViewport,
            int width,
            int height
    ) {
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

        transform.setToIdentity();
        transform.setMxx(scaleX);
        transform.setMyy(scaleY);
        transform.setTx(translateX);
        transform.setTy(translateY);
    }

    public void recolor(ColoringStrategy coloring) {
        if (displayedRenderFrame == null || displayedFrame == null) {
            return;
        }

        colorizer.color(displayedRenderFrame.fractalData(), displayedFrame.intBuffer(), coloring);

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

    public void displayReadyPixels(
            RenderFrame frame,
            ColoringStrategy coloring
    ) {
        if (stagingFrame == null || stagingRenderFrame != frame) {
            return;
        }

        FractalData data = frame.fractalData();
        ValidityMask validity = frame.validity();

        if (data.width() != stagingFrame.width()
                || data.height() != stagingFrame.height()) {
            return;
        }

        for (int y = 0; y < data.height(); y++) {

            int runStart = -1;

            for (int x = 0; x < data.width(); x++) {

                if (validity.isReady(x, y)) {

                    if (runStart < 0) {
                        runStart = x;
                    }

                } else if (runStart >= 0) {

                    colorizer.colorRegion(
                            data,
                            stagingFrame.intBuffer(),
                            coloring,
                            new RenderRegion(
                                    runStart,
                                    y,
                                    x - runStart,
                                    1
                            )
                    );

                    runStart = -1;
                }
            }

            if (runStart >= 0) {
                colorizer.colorRegion(
                        data,
                        stagingFrame.intBuffer(),
                        coloring,
                        new RenderRegion(
                                runStart,
                                y,
                                data.width() - runStart,
                                1
                        )
                );
            }
        }

        stagingFrame.update();
    }

    /** Keeps a visible partial render when its samples are shifted by a pan. */
    public boolean reuseProgressivePixels(
            RenderFrame sourceFrame,
            RenderFrame targetFrame,
            PixelShift shift,
            ColoringStrategy coloring
    ) {
        Objects.requireNonNull(coloring);

        if (stagingFrame == null || stagingRenderFrame != sourceFrame) {
            return false;
        }

        if (!stagingFrame.shiftInPlace(shift)) {
            return false;
        }

        stagingRenderFrame = targetFrame;

        /*
         * A cancelled tile can contain complete rows that are valid but have
         * not produced a tile-level progress event yet. Recolor every reused
         * valid sample so those rows are present in the shifted image before
         * the resumed renderer skips them.
         */
        displayReadyPixels(targetFrame, coloring);

        return true;
    }

    /**
     * Copies shifted pixels from the displayed image when it represents the
     * same source render frame used by the reuse planner.
     */
    public boolean reuseDisplayedPixels(
            RenderFrame sourceFrame,
            PixelShift shift
    ) {
        if (displayedFrame == null
                || stagingFrame == null) {
            return false;
        }

        /* The displayed image must represent the mathematical source frame. */
        if (displayedRenderFrame != sourceFrame) {
            return false;
        }

        if (displayedFrame.width()
                != stagingFrame.width()
                || displayedFrame.height()
                != stagingFrame.height()) {
            return false;
        }

        Rectangle2D dirtyRegion =
                stagingFrame.copyShiftedFrom(
                        displayedFrame,
                        shift);

        if (dirtyRegion == null) {
            return false;
        }

        stagingFrame.pixelBuffer()
                .updateBuffer(
                        ignored -> dirtyRegion
                );

        return true;
    }
}
