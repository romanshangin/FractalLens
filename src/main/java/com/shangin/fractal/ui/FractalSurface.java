package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.Palette;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import com.shangin.fractal.scene.ColoringSettings;
import com.shangin.fractal.scene.FractalScene;
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
    private final Affine retainedPreviewTransform = new Affine();
    private final Affine progressivePreviewTransform = new Affine();
    private final ImageView baseImageView = new ImageView();
    private final ImageView retainedImageView = new ImageView();
    private final ImageView progressiveImageView = new ImageView();
    private final FractalColorizer colorizer = new FractalColorizer();

    private SurfaceBuffer stagingFrame;
    private SurfaceBuffer displayedFrame;
    private SurfaceBuffer retainedProgressFrame;
    private ValidityMask stagingRefinementValidity;
    private ValidityMask displayedRefinementValidity;
    private RenderFrame stagingRenderFrame;
    private RenderFrame displayedRenderFrame;
    private RenderFrame retainedProgressRenderFrame;
    private FractalScene displayedScene;
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
        getChildren().addAll(baseImageView, retainedImageView, progressiveImageView);
    }

    /** Prepares the staging buffer for a new progressive frame. */
    public void beginProgressiveRender(RenderFrame renderFrame) {
        if (renderWidth < 2 || renderHeight < 2) {
            return;
        }

        if (ProgressiveFrameRetention.shouldRetain(
                stagingRenderFrame,
                renderFrame,
                progressiveImageView.getImage() != null
        )) {
            retainVisibleProgress();
            stagingFrame = null;
            stagingRefinementValidity = null;
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
            stagingRefinementValidity = new ValidityMask(renderWidth, renderHeight);
        }

        if (stagingRenderFrame != renderFrame) {
            stagingFrame.clear();
            stagingRefinementValidity.clear();
        }

        stagingRenderFrame = renderFrame;
        progressivePreviewTransform.setToIdentity();

        progressiveImageView.setImage(
                stagingFrame.image()
        );
    }

    private void configureImageViews() {
        configureImageView(baseImageView);
        configureImageView(retainedImageView);
        configureImageView(progressiveImageView);

        baseImageView
                .getTransforms()
                .add(previewTransform);

        retainedImageView
                .getTransforms()
                .add(retainedPreviewTransform);

        progressiveImageView
                .getTransforms()
                .add(progressivePreviewTransform);

        progressiveImageView.setMouseTransparent(true);
        retainedImageView.setMouseTransparent(true);
    }

    private void retainVisibleProgress() {
        retainedProgressFrame = stagingFrame;
        retainedProgressRenderFrame = stagingRenderFrame;
        retainedPreviewTransform.setToTransform(progressivePreviewTransform);
        retainedImageView.setImage(stagingFrame.image());
        progressiveImageView.setImage(null);
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
            FractalScene scene
    ) {

        if (stagingFrame == null) {
            return;
        }

        promoteStagingFrame(renderFrame, scene);
    }

    /** Keeps raw staging pixels hidden while quality-mode samples are calculated. */
    public void hideProgressiveRender(RenderFrame frame) {
        if (stagingRenderFrame == frame) {
            progressiveImageView.setImage(null);
        }
    }

    /** Shows preserved refined pixels and prepares the overlay for new AA tiles. */
    public void beginRefinedRender(RenderFrame frame) {
        if (stagingFrame == null || stagingRenderFrame != frame) {
            return;
        }
        progressivePreviewTransform.setToIdentity();
        progressiveImageView.setImage(stagingFrame.image());
    }

    private void promoteStagingFrame(
            RenderFrame renderFrame,
            FractalScene scene
    ) {
        SurfaceBuffer oldDisplayed =
                displayedFrame;
        ValidityMask oldDisplayedValidity = displayedRefinementValidity;

        displayedFrame =
                stagingFrame;
        displayedRefinementValidity = stagingRefinementValidity;

        stagingFrame =
                oldDisplayed;
        stagingRefinementValidity = oldDisplayedValidity;

        displayedRenderFrame = renderFrame;
        displayedScene = Objects.requireNonNull(scene);
        displayedViewport = scene.viewport();
        stagingRenderFrame = null;

        baseImageView.setImage(
                displayedFrame.image()
        );

        resetPreview();

        progressiveImageView.setImage(null);
        retainedImageView.setImage(null);
        retainedProgressFrame = null;
        retainedProgressRenderFrame = null;
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
        stagingRefinementValidity = new ValidityMask(renderWidth, renderHeight);
        stagingRenderFrame = null;
    }

    public int renderWidth() {
        return renderWidth;
    }

    public int renderHeight() {
        return renderHeight;
    }

    public boolean hasCompletedFrame() {
        return displayedFrame != null;
    }

    public CompletedRender completedRender() {
        if (displayedRenderFrame == null || displayedScene == null) {
            return null;
        }

        return new CompletedRender(displayedScene, displayedRenderFrame);
    }

    /** Applies one refined tile only when it belongs to the visible frame. */
    public void applyAntialiasing(
            RenderFrame frame,
            RenderRegion region,
            int[] colors
    ) {
        if (displayedRenderFrame != frame || displayedFrame == null) {
            return;
        }
        applyTile(displayedFrame, region, colors);
        displayedRefinementValidity.markReady(region);
    }

    /** Publishes one quality-mode AA tile into the current staging frame. */
    public void displayRefinedTile(
            RenderFrame frame,
            RenderRegion region,
            int[] colors
    ) {
        if (stagingRenderFrame != frame || stagingFrame == null) {
            return;
        }
        applyTile(stagingFrame, region, colors);
        stagingRefinementValidity.markReady(region);
    }

    /** Copies current refined colors and their validity for background AA reuse. */
    public RefinedPixelSnapshot refinedPixelSnapshot(RenderFrame frame) {
        if (stagingRenderFrame == frame
                && stagingFrame != null
                && stagingRefinementValidity != null) {
            return new RefinedPixelSnapshot(
                    stagingFrame.width(),
                    stagingFrame.height(),
                    stagingFrame.intBuffer().array(),
                    stagingRefinementValidity
            );
        }
        if (displayedRenderFrame == frame
                && displayedFrame != null
                && displayedRefinementValidity != null) {
            return new RefinedPixelSnapshot(
                    displayedFrame.width(),
                    displayedFrame.height(),
                    displayedFrame.intBuffer().array(),
                    displayedRefinementValidity
            );
        }
        return RefinedPixelSnapshot.empty(frame.request().width(), frame.request().height());
    }

    private static void applyTile(
            SurfaceBuffer buffer,
            RenderRegion region,
            int[] colors
    ) {
        Objects.requireNonNull(region);
        Objects.requireNonNull(colors);

        if (region.x() < 0
                || region.y() < 0
                || region.x() + region.width() > buffer.width()
                || region.y() + region.height() > buffer.height()
                || colors.length != region.width() * region.height()) {
            throw new IllegalArgumentException("AA tile dimensions do not match frame");
        }

        int[] target = buffer.intBuffer().array();
        for (int row = 0; row < region.height(); row++) {
            System.arraycopy(
                    colors,
                    row * region.width(),
                    target,
                    (region.y() + row) * buffer.width() + region.x(),
                    region.width()
            );
        }
        buffer.pixelBuffer().updateBuffer(ignored -> new Rectangle2D(
                region.x(),
                region.y(),
                region.width(),
                region.height()
        ));
    }

    private void resetPreview() {
        previewTransform.setToIdentity();
        retainedPreviewTransform.setToIdentity();
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

        if (retainedProgressRenderFrame != null) {
            applyPreviewTransform(
                    retainedPreviewTransform,
                    retainedProgressRenderFrame.request().viewport(),
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

    public void recolor(
            ColoringStrategy coloring,
            ColoringSettings settings
    ) {
        if (displayedRenderFrame == null || displayedFrame == null) {
            return;
        }

        colorizer.color(displayedRenderFrame.fractalData(), displayedFrame.intBuffer(), coloring);
        invalidateRefinement();

        if (displayedScene != null) {
            displayedScene = displayedScene.withColoring(settings);
        }

        displayedFrame.update();
    }

    public void invalidateRefinement() {
        if (displayedRefinementValidity != null) {
            displayedRefinementValidity.clear();
        }
        if (stagingRefinementValidity != null) {
            stagingRefinementValidity.clear();
        }
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
            ColoringStrategy coloring,
            boolean includeRawReadyPixels
    ) {
        Objects.requireNonNull(coloring);

        if (stagingFrame == null || stagingRenderFrame != sourceFrame) {
            return false;
        }

        if (!stagingFrame.shiftInPlace(shift)) {
            stagingRefinementValidity.clear();
            return false;
        }
        stagingRefinementValidity.copyShiftedFrom(stagingRefinementValidity, shift);

        stagingRenderFrame = targetFrame;

        /*
         * A cancelled tile can contain complete rows that are valid but have
         * not produced a tile-level progress event yet. Recolor every reused
         * valid sample so those rows are present in the shifted image before
         * the resumed renderer skips them.
         */
        if (includeRawReadyPixels) {
            displayReadyPixels(targetFrame, coloring);
        }

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

        stagingRefinementValidity.copyShiftedFrom(
                displayedRefinementValidity,
                shift
        );

        return true;
    }
}
