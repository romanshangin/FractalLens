package com.shangin.fractal.ui;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Visible JavaFX software-boundary probe; never used by application startup. */
public final class PublicationPathProbe {
    private static final int WIDTH = 1512;
    private static final int HEIGHT = 982;
    private static final int SIDE = 128;
    private static final int WARMUPS = 20;
    private static final int SAMPLES = 80;
    private static final Variant[] VARIANTS = Variant.values();

    private PublicationPathProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected a new output CSV path");
        }
        Path output = Path.of(args[0]);
        if (Files.exists(output)) {
            throw new IllegalArgumentException("Output already exists: " + output);
        }
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> {
            Stage stage = new Stage();
            ImageView image = new ImageView();
            SurfaceBuffer reusable = new SurfaceBuffer(WIDTH, HEIGHT);
            int[] source = new int[WIDTH * HEIGHT];
            java.util.Arrays.fill(source, 0xff806040);
            image.setImage(reusable.image());
            image.setFitWidth(756);
            image.setFitHeight(491);
            stage.setScene(new Scene(new StackPane(image), 756, 491));
            stage.show();

            new AnimationTimer() {
                private final List<Row> rows = new ArrayList<>();
                private int frame;
                private long previousPulse;

                @Override
                public void handle(long now) {
                    int sample = frame / VARIANTS.length;
                    Variant variant = VARIANTS[(frame + sample) % VARIANTS.length];
                    long previousInterval = previousPulse == 0 ? -1 : now - previousPulse;
                    previousPulse = now;
                    long start = System.nanoTime();
                    switch (variant) {
                        case DIRTY -> {
                            writeSquare(reusable, sample);
                            reusable.pixelBuffer().updateBuffer(ignored ->
                                    new Rectangle2D(0, 0, SIDE, SIDE));
                            image.setImage(reusable.image());
                        }
                        case FULL -> {
                            writeSquare(reusable, sample);
                            reusable.update();
                            image.setImage(reusable.image());
                        }
                        case COPY_FULL -> {
                            System.arraycopy(source, 0, reusable.intBuffer().array(), 0, source.length);
                            reusable.update();
                            image.setImage(reusable.image());
                        }
                        case REUSE_CLEAR -> {
                            reusable.clear();
                            image.setImage(reusable.image());
                        }
                        case NEW_BUFFER -> {
                            SurfaceBuffer fresh = new SurfaceBuffer(WIDTH, HEIGHT);
                            image.setImage(fresh.image());
                        }
                    }
                    long operation = System.nanoTime() - start;
                    if (sample >= WARMUPS) {
                        rows.add(new Row(sample - WARMUPS, variant, operation, previousInterval));
                    }
                    frame++;
                    if (frame == (WARMUPS + SAMPLES) * VARIANTS.length) {
                        stop();
                        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
                            writer.write("sample,variant,operation_ns,preceding_pulse_ns\n");
                            for (Row row : rows) {
                                writer.write(row.sample + "," + row.variant + "," + row.operation + ","
                                        + row.precedingPulse + "\n");
                            }
                        } catch (Exception error) {
                            failure.set(error);
                        } finally {
                            stage.close();
                            Platform.exit();
                            finished.countDown();
                        }
                    }
                }
            }.start();
        });
        if (!finished.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("JavaFX probe timed out");
        }
        if (failure.get() != null) {
            throw new IllegalStateException("JavaFX probe failed", failure.get());
        }
    }

    private static void writeSquare(SurfaceBuffer buffer, int sample) {
        int[] pixels = buffer.intBuffer().array();
        for (int y = 0; y < SIDE; y++) {
            java.util.Arrays.fill(pixels, y * WIDTH, y * WIDTH + SIDE, 0xff000000 | sample);
        }
    }

    private enum Variant { DIRTY, FULL, COPY_FULL, REUSE_CLEAR, NEW_BUFFER }

    private record Row(int sample, Variant variant, long operation, long precedingPulse) {}
}
