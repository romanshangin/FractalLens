package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.Palette;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import com.shangin.fractal.scene.ColoringSettings;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.InteractiveRenderMode;
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
import java.util.Optional;

/**
 * JavaFX surface that owns display buffers, applies interaction previews, and
 * publishes progressively colored regions to a PixelBuffer-backed image.
 */
public final class FractalSurface extends Region {

    private final InteractionLatency latency = new InteractionLatency();

    public InteractionLatency latency() { return latency; }

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
    private ValidityMask retainedProgressRefinementValidity;
    private RenderFrame stagingRenderFrame;
    private RenderFrame displayedRenderFrame;
    private RenderFrame retainedProgressRenderFrame;
    private FractalScene displayedScene;
    private Viewport displayedViewport;
    private Viewport previewTargetViewport;
    private boolean initialResizePreview;
    private boolean stagingShowsBaseProgress;

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
    public void beginProgressiveRender(
            RenderFrame renderFrame,
            RenderFrame plannerSourceFrame,
            InteractiveRenderMode renderMode
    ) {
        Objects.requireNonNull(renderMode);

        if (renderWidth < 2 || renderHeight < 2) {
            return;
        }

        if (ProgressiveFrameRetention.shouldReplaceRetained(
                stagingRenderFrame,
                renderFrame,
                progressiveImageView.getImage() != null,
                plannerSourceFrame,
                retainedProgressRenderFrame
        )) {
            retainVisibleProgress();
            stagingFrame = null;
            stagingRefinementValidity = null;
        } else if (stagingRenderFrame != null && stagingRenderFrame != renderFrame) {
            progressiveImageView.setImage(null);
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
        stagingShowsBaseProgress = !initialResizePreview && plannerSourceFrame != null
                && (plannerSourceFrame.request().width() != renderFrame.request().width()
                || plannerSourceFrame.request().height() != renderFrame.request().height());

        progressiveImageView.setImage(
                !initialResizePreview && (stagingShowsBaseProgress
                        || InteractiveRenderPresentation.showsBaseProgress(renderMode))
                        ? stagingFrame.image()
                        : null
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
        retainedProgressRefinementValidity = stagingRefinementValidity;
        retainedPreviewTransform.setToTransform(progressivePreviewTransform);
        retainedImageView.setImage(initialResizePreview ? null : stagingFrame.image());
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
            ColoringStrategy coloring,
            InteractiveRenderMode renderMode
    ) {
        Objects.requireNonNull(renderMode);

        if (!stagingShowsBaseProgress
                && !InteractiveRenderPresentation.showsBaseProgress(renderMode)) {
            return;
        }

        if (stagingFrame == null) {
            return;
        }

        RenderFrame renderFrame = progress.frame();

        if (stagingRenderFrame != renderFrame) {
            return;
        }

        SamplePlane data = renderFrame.samplePlane();

        if (data.width() != stagingFrame.width() || data.height() != stagingFrame.height()) {
            return;
        }

        if (progress.regions().isEmpty()) {
            return;
        }

        ValidityMask refinementToPreserve = stagingRefinementValidity.readyPixelCount() == 0
                ? null
                : stagingRefinementValidity;
        for (RenderRegion region : progress.regions()) {
            colorizer.colorRegion(
                    data,
                    stagingFrame.intBuffer(),
                    coloring,
                    region,
                    refinementToPreserve
            );
        }

        Rectangle2D dirtyRegion = dirtyRegion(progress.regions());

        stagingFrame.pixelBuffer().updateBuffer(
                pixelBuffer -> dirtyRegion
        );
        if (progressiveImageView.getImage() == stagingFrame.image()) latency.mark("base_publish");

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

        if (stagingFrame == null || stagingRenderFrame != renderFrame) {
            return;
        }

        promoteStagingFrame(renderFrame, scene);
    }

    /** Shows preserved refined pixels and prepares the overlay for new AA tiles. */
    public void beginRefinedRender(RenderFrame frame) {
        if (stagingFrame == null || stagingRenderFrame != frame) {
            return;
        }
        // During resize, keep newly calculated base colors visible at the edges;
        // AA tiles replace them in place. Other refined renders retain the
        // established quality-only presentation.
        if (!stagingShowsBaseProgress) {
            stagingFrame.clearExcept(stagingRefinementValidity);
        }
        progressivePreviewTransform.setToIdentity();
        progressiveImageView.setImage(initialResizePreview ? null : stagingFrame.image());
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
        stagingShowsBaseProgress = false;

        baseImageView.setImage(
                displayedFrame.image()
        );

        resetPreview();

        progressiveImageView.setImage(null);
        retainedImageView.setImage(null);
        retainedProgressFrame = null;
        retainedProgressRenderFrame = null;
        retainedProgressRefinementValidity = null;
        latency.mark("frame_promoted");
    }

    public void resizeBuffer(
            int logicalWidth,
            int logicalHeight
    ) {
        // Update dimensions even when the spare buffer happens to match.
        // Keep visible progress until beginProgressiveRender can retain/copy it.
        renderWidth = renderWidthFor(logicalWidth);
        renderHeight = renderHeightFor(logicalHeight);
    }

    // Equal parity keeps the center on the same sample grid during resize.
    public int renderWidthFor(int logicalWidth) {
        return Math.max(2, 2 * (int) Math.ceil(logicalWidth * outputScaleX / 2));
    }

    public int renderHeightFor(int logicalHeight) {
        return Math.max(2, 2 * (int) Math.ceil(logicalHeight * outputScaleY / 2));
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

    /** Sample completion alone does not guarantee that AA colors were published. */
    public boolean hasCompleteRefinement(RenderFrame frame) {
        return stagingRenderFrame == frame && stagingRefinementValidity != null
                && stagingRefinementValidity.isComplete();
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
        latency.mark("aa_publish");
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
        latency.mark("aa_publish");
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
        initialResizePreview = false;
        previewTransform.setToIdentity();
        retainedPreviewTransform.setToIdentity();
        progressivePreviewTransform.setToIdentity();
        previewTargetViewport = null;
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
        initialResizePreview = false;
        showPreview(targetViewport, renderWidth, renderHeight);
    }

    /** Keep one full-window image until a refitted overview is ready to replace it. */
    public void showInitialResizePreview() {
        if (displayedFrame == null) {
            return;
        }
        resetPreview();
        initialResizePreview = true;
        // ImageView fit dimensions follow the surface, so this stretches without
        // accumulating projections from intermediate window aspect ratios.
        baseImageView.setImage(displayedFrame.image());
        retainedImageView.setImage(null);
        progressiveImageView.setImage(null);
    }

    /** Projects every visible layer using its own source dimensions. */
    public void showResizePreview(Viewport targetViewport, int targetWidth, int targetHeight) {
        initialResizePreview = false;
        showPreview(targetViewport, targetWidth, targetHeight);
        // A resized image is presentation only, never evidence of computed samples.
        previewTargetViewport = null;
    }

    private void showPreview(Viewport targetViewport, int targetWidth, int targetHeight) {

        double width = getWidth();
        double height = getHeight();

        if (width < 2 || height < 2 || renderWidth < 2 || renderHeight < 2) {
            return;
        }

        previewTargetViewport = targetViewport;

        if (displayedViewport != null) {
            applyPreviewTransform(
                previewTransform,
                displayedViewport,
                targetViewport,
                displayedFrame.width(),
                displayedFrame.height(),
                targetWidth,
                targetHeight,
                width,
                height
            );
        }

        if (stagingRenderFrame != null) {
            applyPreviewTransform(
                    progressivePreviewTransform,
                    stagingRenderFrame.request().viewport(),
                    targetViewport,
                    stagingRenderFrame.request().width(),
                    stagingRenderFrame.request().height(),
                    targetWidth,
                    targetHeight,
                    width,
                    height
            );
        }

        if (retainedProgressRenderFrame != null) {
            applyPreviewTransform(
                    retainedPreviewTransform,
                    retainedProgressRenderFrame.request().viewport(),
                    targetViewport,
                    retainedProgressRenderFrame.request().width(),
                    retainedProgressRenderFrame.request().height(),
                    targetWidth,
                    targetHeight,
                    width,
                    height
            );
        }
        if (displayedViewport != null || stagingRenderFrame != null || retainedProgressRenderFrame != null) {
            latency.preview();
        }
    }

    /** Returns the display-only area covered by a scaled zoom-out preview. */
    public Optional<RenderRegion> approximatePreviewCoverage(Viewport targetViewport) {
        Objects.requireNonNull(targetViewport);

        if (!targetViewport.equals(previewTargetViewport)
                || displayedViewport == null
                || displayedFrame == null
                || renderWidth < 2
                || renderHeight < 2) {
            return Optional.empty();
        }

        return FrameReprojection.approximateCoverage(
                displayedViewport,
                targetViewport,
                renderWidth,
                renderHeight
        );
    }

    private static void applyPreviewTransform(
            Affine transform,
            Viewport source,
            Viewport targetViewport,
            int sourceWidth,
            int sourceHeight,
            int targetWidth,
            int targetHeight,
            double displayWidth,
            double displayHeight
    ) {
        ViewportProjection projection = ViewportProjection.betweenImageBounds(
                source, targetViewport,
                sourceWidth, sourceHeight,
                targetWidth, targetHeight,
                displayWidth, displayHeight);

        transform.setToIdentity();
        transform.setMxx(projection.scaleX());
        transform.setMyy(projection.scaleY());
        transform.setTx(projection.translateX());
        transform.setTy(projection.translateY());
    }

    public void recolor(
            ColoringStrategy coloring,
            ColoringSettings settings
    ) {
        if (displayedRenderFrame == null || displayedFrame == null) {
            return;
        }

        colorizer.color(displayedRenderFrame.samplePlane(), displayedFrame.intBuffer(), coloring);
        invalidateRefinement();

        if (displayedScene != null) {
            displayedScene = displayedScene.withColoring(settings);
        }

        displayedFrame.update();
    }

    /** Atomically publishes a background-computed palette frame. */
    public void applyRecolor(
            RenderFrame frame,
            int[] colors,
            ColoringSettings settings
    ) {
        if (displayedRenderFrame != frame || displayedFrame == null
                || colors.length != displayedFrame.width() * displayedFrame.height()) {
            return;
        }
        displayedFrame.publish(colors);
        if (displayedScene != null) {
            displayedScene = displayedScene.withColoring(settings);
        }
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
        displayReadyPixels(frame, coloring, false);
    }

    /** Colors ready base samples without overwriting shifted AA pixels. */
    public void displayReadyPixelsPreservingRefinement(
            RenderFrame frame,
            ColoringStrategy coloring
    ) {
        displayReadyPixels(frame, coloring, true);
    }

    private void displayReadyPixels(
            RenderFrame frame,
            ColoringStrategy coloring,
            boolean preserveRefinement
    ) {
        if (stagingFrame == null || stagingRenderFrame != frame) {
            return;
        }

        SamplePlane data = frame.samplePlane();
        ValidityMask validity = frame.validity();

        if (data.width() != stagingFrame.width()
                || data.height() != stagingFrame.height()) {
            return;
        }

        colorizer.colorReadyPixels(
                data,
                stagingFrame.intBuffer(),
                coloring,
                validity,
                preserveRefinement ? stagingRefinementValidity : null
        );

        stagingFrame.update();
        if (latency.isEnabled() && validity.readyPixelCount() > 0
                && progressiveImageView.getImage() == stagingFrame.image()) {
            latency.mark("base_publish");
        }
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

        if (!stagingFrame.matches(targetFrame.request().width(), targetFrame.request().height())) {
            retainVisibleProgress();
            stagingFrame = null;
            stagingRefinementValidity = null;
            stagingRenderFrame = null;
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
            displayReadyPixelsPreservingRefinement(targetFrame, coloring);
        }

        return true;
    }

    /** Copies shifted refined pixels from a visible source selected by the planner. */
    public boolean reuseDisplayedPixels(
            RenderFrame sourceFrame,
            PixelShift shift
    ) {
        if (stagingFrame == null) {
            return false;
        }

        ProgressiveFrameRetention.SourceSlot sourceSlot =
                ProgressiveFrameRetention.reusableSource(
                        sourceFrame,
                        displayedRenderFrame,
                        retainedProgressRenderFrame
                );
        SurfaceBuffer sourceSurface;
        ValidityMask sourceValidity;

        if (sourceSlot == ProgressiveFrameRetention.SourceSlot.DISPLAYED) {
            sourceSurface = displayedFrame;
            sourceValidity = displayedRefinementValidity;
        } else if (sourceSlot == ProgressiveFrameRetention.SourceSlot.RETAINED) {
            sourceSurface = retainedProgressFrame;
            sourceValidity = retainedProgressRefinementValidity;
        } else {
            return false;
        }

        if (sourceSurface == null || sourceValidity == null) {
            return false;
        }

        Rectangle2D dirtyRegion =
                stagingFrame.copyShiftedFrom(
                        sourceSurface,
                        shift);

        if (dirtyRegion == null) {
            return false;
        }

        stagingFrame.pixelBuffer()
                .updateBuffer(
                        ignored -> dirtyRegion
                );

        stagingRefinementValidity.copyShiftedFrom(
                sourceValidity,
                shift
        );

        return true;
    }
}
