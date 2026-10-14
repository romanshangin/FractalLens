package com.shangin.fractal.ui;

import com.shangin.fractal.app.BookmarkStore;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.FractalSceneJson;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
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
import java.util.function.BooleanSupplier;

/** Scene sharing and durable bookmarks; all storage work stays off the FX thread. */
final class SceneActions implements AutoCloseable {
    private final Stage owner;
    private final FractalView view;
    private final BookmarkStore store;
    private final BooleanSupplier sheetOpen;
    private MacBookmarkSheet bookmarkSheet;
    private final Menu bookmarks = new Menu("Bookmarks");
    private final ExecutorService storage = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("scene-bookmarks").factory());
    private boolean closed;
    private final BooleanProperty busy = new SimpleBooleanProperty();

    SceneActions(Stage owner, FractalView view, BookmarkStore store, BooleanSupplier sheetOpen) {
        this.owner = owner;
        this.view = view;
        this.store = store;
        this.sheetOpen = sheetOpen;
        runStorage(store::load);
    }

    Menu bookmarksMenu() { return bookmarks; }

    boolean hasOpenSheet() { return bookmarkSheet != null && bookmarkSheet.isOpen(); }

    private void addBookmark() {
        if (closed || busy.get() || sheetOpen.getAsBoolean()) return;
        FractalScene snapshot = view.sceneSnapshot();
        java.util.function.Consumer<String> save = name ->
                runStorage(() -> { store.save(name, snapshot); return store.load(); });
        if (MacBookmarkSheet.isAvailable()) {
            try {
                bookmarkSheet = MacBookmarkSheet.show(owner, save);
                return;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
                System.getLogger(SceneActions.class.getName()).log(System.Logger.Level.WARNING,
                        "Native bookmark sheet unavailable; using JavaFX dialog", error);
            }
        }
        FractalDialogs.bookmarkNameDialog(owner).showAndWait().ifPresent(save);
    }

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
        MenuItem save = command("Add Bookmark…", this::addBookmark);
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
        if (closed || busy.get()) return;
        busy.set(true);
        storage.execute(() -> {
            try {
                List<BookmarkStore.Bookmark> entries = operation.run();
                Platform.runLater(() -> {
                    if (closed) return;
                    rebuild(entries);
                    // Publish children while busy, then explicitly enable each command.
                    // Re-enabling only a parent leaves stale disabled AppKit items.
                    busy.set(false);
                });
            } catch (IOException exception) {
                Platform.runLater(() -> {
                    if (closed) return;
                    // Keep existing entries after a failed write; startup offers a retry.
                    if (bookmarks.getItems().isEmpty()) {
                        bookmarks.getItems().setAll(command("Reload Bookmarks", () -> runStorage(store::load)));
                    }
                    busy.set(false);
                    error("Bookmark storage failed", "Could not read or update bookmarks. Check the application data directory and retry.");
                });
            }
        });
    }

    private void error(String title, String detail) {
        FractalDialogs.messageDialog(owner, Alert.AlertType.ERROR, title, title, detail).show();
    }

    private MenuItem command(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.disableProperty().bind(busy);
        item.setOnAction(event -> action.run());
        return item;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (bookmarkSheet != null) bookmarkSheet.close();
        storage.shutdown();
    }
}
