package com.shangin.fractal.ui;

import com.shangin.fractal.app.BookmarkStore;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.FractalSceneJson;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Scene sharing and durable bookmarks; all storage work stays off the FX thread. */
final class SceneActions implements AutoCloseable {
    private final Stage owner;
    private final FractalView view;
    private final BookmarkStore store;
    private final Menu bookmarks = new Menu("Bookmarks");
    private final ExecutorService storage = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("scene-bookmarks").factory());
    private boolean closed;
    private boolean busy;

    SceneActions(Stage owner, FractalView view, BookmarkStore store) {
        this.owner = owner;
        this.view = view;
        this.store = store;
        bookmarks.setDisable(true);
        runStorage(store::load);
    }

    Menu bookmarksMenu() { return bookmarks; }

    void copyPreset() {
        ClipboardContent content = new ClipboardContent();
        content.putString(FractalSceneJson.write(view.sceneSnapshot()));
        Clipboard.getSystemClipboard().setContent(content);
    }

    void pastePreset() {
        try {
            String json = Clipboard.getSystemClipboard().getString();
            if (json == null) throw new IllegalArgumentException("Copy a FractalLens scene JSON preset first.");
            FractalScene scene = FractalSceneJson.read(json);
            view.applyScene(scene);
        } catch (IllegalArgumentException exception) {
            error("Invalid scene preset", exception.getMessage());
        }
    }

    private void rebuild(List<BookmarkStore.Bookmark> entries) {
        MenuItem save = command("Add Bookmark…", () -> {
            if (busy) return;
            FractalScene snapshot = view.sceneSnapshot();
            FractalDialogs.bookmarkNameDialog(owner).showAndWait().ifPresent(name ->
                    runStorage(() -> { store.save(name, snapshot); return store.load(); }));
        });
        MenuItem reload = command("Reload Bookmarks", () -> runStorage(store::load));
        bookmarks.getItems().setAll(save, reload, new SeparatorMenuItem());
        for (var entry : entries) {
            Menu bookmark = new Menu(entry.name());
            bookmark.getItems().setAll(command("Restore", () -> {
                try {
                    view.applyScene(entry.scene());
                } catch (IllegalArgumentException exception) {
                    error("Bookmark unavailable", exception.getMessage());
                }
            }), command("Delete", () -> runStorage(() -> {
                store.delete(entry.name());
                return store.load();
            })));
            bookmarks.getItems().add(bookmark);
        }
    }

    @FunctionalInterface
    private interface StorageOperation {
        List<BookmarkStore.Bookmark> run() throws IOException;
    }

    private void runStorage(StorageOperation operation) {
        if (closed || busy) return;
        busy = true;
        bookmarks.setDisable(true);
        storage.execute(() -> {
            try {
                List<BookmarkStore.Bookmark> entries = operation.run();
                Platform.runLater(() -> {
                    if (closed) return;
                    busy = false;
                    rebuild(entries);
                    bookmarks.setDisable(false);
                });
            } catch (IOException exception) {
                Platform.runLater(() -> {
                    if (closed) return;
                    busy = false;
                    // Keep existing entries after a failed write; startup offers a retry.
                    if (bookmarks.getItems().isEmpty()) {
                        bookmarks.getItems().setAll(command("Reload Bookmarks", () -> runStorage(store::load)));
                    }
                    bookmarks.setDisable(false);
                    error("Bookmark storage failed", "Could not read or update bookmarks. Check the application data directory and retry.");
                });
            }
        });
    }

    private void error(String title, String detail) {
        FractalDialogs.messageDialog(owner, Alert.AlertType.ERROR, title, title, detail).show();
    }

    private static MenuItem command(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(event -> action.run());
        return item;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        storage.shutdown();
    }
}
