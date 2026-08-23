package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.Palette;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.FractalColorizer;
import com.shangin.fractal.render.FractalData;
import javafx.geometry.Insets;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Affine;
import javafx.stage.Window;

import java.nio.IntBuffer;
import java.util.Objects;

public final class FractalSurface extends Region {

    private final Affine previewTransform = new Affine();
    private final ImageView imageView = new ImageView();
    private final FractalColorizer colorizer = new FractalColorizer();

    // buffer for the next frame
    private IntBuffer renderBuffer;
    private PixelBuffer<IntBuffer> renderPixelBuffer;
    private WritableImage renderImage;

    // current picture on the screen
    private IntBuffer displayedBuffer;
    private PixelBuffer<IntBuffer> displayedPixelBuffer;
    private WritableImage displayedImage;
    private FractalData displayedData;
    private Viewport displayedViewport;

    private Runnable renderScaleChangedHandler = () -> {};

    private int renderWidth;
    private int renderHeight;
    private double outputScaleX = 1.0;
    private double outputScaleY = 1.0;

    public FractalSurface() {
        setMinSize(0, 0);
        configureImageView();
        configureClip();
        configureHiDpi();
        getChildren().add(imageView);
    }

    public void resizeBuffer(
            int logicalWidth,
            int logicalHeight
    ) {
        int newRenderWidth = Math.max(2, (int) Math.ceil(logicalWidth * outputScaleX));
        int newRenderHeight = Math.max(2, (int) Math.ceil(logicalHeight * outputScaleY));

        if (newRenderWidth == renderWidth && newRenderHeight == renderHeight && renderBuffer != null) {
            return;
        }
        renderWidth = newRenderWidth;
        renderHeight = newRenderHeight;
        createRenderBuffer();
    }

    public int renderWidth() {
        return renderWidth;
    }

    public int renderHeight() {
        return renderHeight;
    }

    private void createRenderBuffer() {
        renderBuffer = IntBuffer.allocate(renderWidth * renderHeight);
        renderPixelBuffer = new PixelBuffer<>(
                renderWidth,
                renderHeight,
                renderBuffer,
                PixelFormat.getIntArgbPreInstance());

        renderImage = new WritableImage(renderPixelBuffer);
    }

    private void resetPreview() {
        previewTransform.setToIdentity();
    }

    private void configureImageView() {
        imageView.setManaged(false);
        imageView.setPreserveRatio(false);
        imageView.setSmooth(false);
        imageView.fitWidthProperty().bind(widthProperty());
        imageView.fitHeightProperty().bind(heightProperty());
        imageView.getTransforms().add(previewTransform);
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
        if (data.width() != renderWidth || data.height() != renderHeight) {
            throw new IllegalArgumentException("Fractal data dimensions do not match render buffer");
        }

        colorizer.color(data, renderBuffer, coloring);

        renderPixelBuffer.updateBuffer(pixelBuffer -> null);

        // new frame is ready
        displayedBuffer = renderBuffer;
        displayedPixelBuffer = renderPixelBuffer;
        displayedImage = renderImage;
        displayedData = data;
        displayedViewport = viewport;
        resetPreview();
        imageView.setImage(displayedImage);
    }

    public void recolor(ColoringStrategy coloring) {
        if (displayedData == null || displayedBuffer == null || displayedPixelBuffer == null) {
            return;
        }

        colorizer.color(displayedData, displayedBuffer, coloring);

        displayedPixelBuffer.updateBuffer(pixelBuffer -> null);
    }

    private void applyColoring(ColoringStrategy coloring)
    {
        if (displayedData == null || displayedBuffer == null || displayedPixelBuffer == null) {
            return;
        }

        colorizer.color(displayedData, displayedBuffer, coloring);

        displayedPixelBuffer.updateBuffer(pixelBuffer -> null);
    }

    public void setPreviewBackground(Palette palette) {
        int argb = palette.middleColor();

        Color color = toFxColor(argb);

        setBackground(new Background(new BackgroundFill(color, CornerRadii.EMPTY, Insets.EMPTY)));
    }

    private Color toFxColor(int argb) {
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
