package com.shangin.fractal.export;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Writes a premultiplied ARGB render buffer as a PNG image. */
public final class PngExporter {

    private PngExporter() {}

    public static void write(
            Path path,
            int width,
            int height,
            int[] premultipliedArgb
    ) throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(premultipliedArgb);

        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Image dimensions must be positive");
        }

        if (premultipliedArgb.length != width * height) {
            throw new IllegalArgumentException("Pixel count must match image dimensions");
        }

        BufferedImage image = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_INT_ARGB_PRE
        );

        int[] targetPixels = ((DataBufferInt) image
                .getRaster()
                .getDataBuffer())
                .getData();

        System.arraycopy(
                premultipliedArgb,
                0,
                targetPixels,
                0,
                premultipliedArgb.length
        );

        if (!ImageIO.write(image, "png", path.toFile())) {
            throw new IOException("No PNG image writer is available");
        }
    }
}
