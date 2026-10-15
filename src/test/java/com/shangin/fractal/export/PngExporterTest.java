package com.shangin.fractal.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PngExporterTest {

    @TempDir
    Path tempDirectory;

    @Test
    void cancellationIsCheckedDuringPngEncoding() {
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(java.util.concurrent.CancellationException.class, () -> PngExporter.write(
                tempDirectory.resolve("cancelled.png"), 16, 16, new int[256],
                () -> checks.incrementAndGet() > 18));
        assertTrue(checks.get() > 18);
    }

    @Test
    void writesPixelsAtRequestedDimensions() throws Exception {
        Path target = tempDirectory.resolve("fractal.png");
        int[] pixels = {
                0xFFFF0000, 0xFF00FF00,
                0xFF0000FF, 0xFFFFFFFF
        };

        PngExporter.write(target, 2, 2, pixels);

        BufferedImage image = ImageIO.read(target.toFile());

        assertEquals(2, image.getWidth());
        assertEquals(2, image.getHeight());
        assertEquals(0xFFFF0000, image.getRGB(0, 0));
        assertEquals(0xFF00FF00, image.getRGB(1, 0));
        assertEquals(0xFF0000FF, image.getRGB(0, 1));
        assertEquals(0xFFFFFFFF, image.getRGB(1, 1));
    }

    @Test
    void rejectsMismatchedPixelCount() {
        Path target = tempDirectory.resolve("fractal.png");

        assertThrows(
                IllegalArgumentException.class,
                () -> PngExporter.write(target, 2, 2, new int[3])
        );
    }
}
