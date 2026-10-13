package com.shangin.fractal.app;

import com.shangin.fractal.scene.FractalScene;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.List;

/** Named versioned scene files, using the session store's atomic recovery protocol. */
public final class BookmarkStore {
    public record Bookmark(String name, FractalScene scene) {}
    private final Path directory;

    public BookmarkStore(Path directory) {
        this.directory = directory;
    }

    public static BookmarkStore systemDefault() {
        return new BookmarkStore(LastSessionStore.defaultPath().resolveSibling("bookmarks"));
    }

    public static String validateName(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.getBytes(StandardCharsets.UTF_8).length > 80
                || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Enter a name of 1 to 80 UTF-8 bytes without control characters.");
        }
        return trimmed;
    }

    private Path file(String name) {
        String encoded = HexFormat.of().formatHex(validateName(name).getBytes(StandardCharsets.UTF_8));
        return directory.resolve(encoded + ".json");
    }

    public List<Bookmark> load() throws IOException {
        if (!Files.exists(directory)) return List.of();
        try (var files = Files.list(directory)) {
            List<Path> paths = files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted().toList();
            var bookmarks = new java.util.ArrayList<Bookmark>();
            for (Path path : paths) {
                String filename = path.getFileName().toString();
                try {
                    String name = validateName(new String(HexFormat.of().parseHex(
                            filename.substring(0, filename.length() - 5)), StandardCharsets.UTF_8));
                    if (!file(name).equals(path)) throw new IllegalArgumentException("Invalid bookmark filename");
                    FractalScene scene = new LastSessionStore(path).load()
                            .orElseThrow(() -> new IOException("A bookmark has no valid scene or recovery copy"));
                    bookmarks.add(new Bookmark(name, scene));
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid bookmark name", exception);
                }
            }
            bookmarks.sort(Comparator.comparing(Bookmark::name));
            return List.copyOf(bookmarks);
        }
    }

    /** Saving an existing name replaces that bookmark and retains its previous generation. */
    public void save(String name, FractalScene scene) throws IOException {
        new LastSessionStore(file(name)).save(scene);
    }

    public void delete(String name) throws IOException {
        Path path = file(name);
        // Delete recovery first so a failed primary deletion leaves the bookmark visible.
        Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".backup"));
        Files.deleteIfExists(path);
    }
}
