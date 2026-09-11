package com.shangin.fractal.ui;

import javafx.application.Platform;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/** One AppKit tracking session. All entry points and disposal belong to the FX thread. */
final class MacContextMenu implements AutoCloseable {
    private long handle;
    private boolean closed;
    private final Consumer<Boolean> completion;

    private static final class Library {
        static final boolean AVAILABLE = load();
        private static boolean load() {
            if (!System.getProperty("os.name", "").startsWith("Mac")) return false;
            try (var source = MacContextMenu.class.getResourceAsStream("native/libfractal-menu.dylib")) {
                if (source == null) throw new IOException("Packaged AppKit menu library is missing");
                var library = Files.createTempFile("fractalui-menu-", ".dylib");
                library.toFile().deleteOnExit();
                Files.copy(source, library, StandardCopyOption.REPLACE_EXISTING);
                System.load(library.toAbsolutePath().toString());
                return true;
            } catch (IOException | LinkageError | RuntimeException error) {
                System.getLogger(MacContextMenu.class.getName()).log(System.Logger.Level.WARNING,
                        "Native canvas menu unavailable; using JavaFX menu", error);
                return false;
            }
        }
    }

    static boolean isAvailable() { return Library.AVAILABLE; }

    static MacContextMenu show(Window window, double sceneX, double sceneY,
                               Consumer<Boolean> completion) throws ReflectiveOperationException {
        requireFxThread();
        Object peer = Class.forName("com.sun.javafx.stage.WindowHelper")
                .getMethod("getPeer", Window.class).invoke(null, window);
        if (peer == null) throw new IllegalStateException("Window has no native peer");
        long windowHandle = (long) Class.forName("com.sun.javafx.tk.TKStage")
                .getMethod("getRawHandle").invoke(peer);
        if (windowHandle == 0) throw new IllegalStateException("Window has no native handle");
        var session = new MacContextMenu(completion);
        session.handle = session.open(windowHandle, sceneX, sceneY);
        if (session.handle == 0) throw new IllegalStateException("Cannot create native menu");
        return session;
    }

    private MacContextMenu(Consumer<Boolean> completion) { this.completion = completion; }

    // Called by AppKit after tracking has finished. Never run application commands in JNI.
    @SuppressWarnings("unused")
    private void completed(boolean selected) {
        Platform.runLater(() -> {
            if (closed) return;
            close();
            completion.accept(selected);
        });
    }

    @Override public void close() {
        requireFxThread();
        if (closed) return;
        closed = true;
        if (handle != 0) {
            dispose(handle);
            handle = 0;
        }
    }

    private static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("FX thread required");
    }

    private native long open(long window, double x, double y);
    private static native void dispose(long handle);
}
