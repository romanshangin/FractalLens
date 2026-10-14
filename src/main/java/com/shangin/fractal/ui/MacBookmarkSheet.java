package com.shangin.fractal.ui;

import com.shangin.fractal.app.BookmarkStore;
import javafx.stage.Window;

import java.util.function.Consumer;

/** Native bookmark name editor sharing validation with storage and the JavaFX form. */
final class MacBookmarkSheet extends MacParameterSheet<String> {
    static MacBookmarkSheet show(Window owner, Consumer<String> save) throws ReflectiveOperationException {
        var sheet = new MacBookmarkSheet(owner, save);
        sheet.showSingleFieldNative("Add Bookmark", "Save the current scene. Existing names are replaced.",
                "Name", "bookmark-name", BookmarkStore.NAME_ERROR, "Save");
        return sheet;
    }

    private MacBookmarkSheet(Window owner, Consumer<String> save) { super(owner, save); }

    @Override int invalidField(String first, String second) {
        try { BookmarkStore.validateName(first); return 0; }
        catch (IllegalArgumentException invalid) { return 1; }
    }

    @Override String parse(String first, String second) { return BookmarkStore.validateName(first); }
}
