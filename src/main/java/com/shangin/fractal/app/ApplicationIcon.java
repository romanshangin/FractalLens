package com.shangin.fractal.app;

import javafx.scene.image.Image;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.Taskbar;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;

final class ApplicationIcon {

    private ApplicationIcon() {
    }

    static void install(Stage stage) {
        URL resource = Objects.requireNonNull(
                ApplicationIcon.class.getResource("icons/fractallens.png"),
                "Missing FractalLens application icon");
        Image icon = new Image(resource.toExternalForm());
        if (icon.isError()) {
            throw new IllegalStateException("Cannot load FractalLens application icon", icon.getException());
        }
        boolean mac = System.getProperty("os.name", "").startsWith("Mac");
        if (!mac) {
            stage.getIcons().add(icon);
        }

        // The macOS Dock belongs to the application, not to an individual stage.
        if (mac && Taskbar.isTaskbarSupported()) {
            Taskbar taskbar = Taskbar.getTaskbar();
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                try {
                    taskbar.setIconImage(ImageIO.read(resource));
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot load FractalLens Dock icon", e);
                }
            }
        }
    }
}
