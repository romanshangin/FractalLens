package com.shangin.fractal.app;

import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.FractalSceneJson;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;

/** Durable storage for the most recently closed scene. */
public final class LastSessionStore {

    private static final String FILE_NAME = "last-session.json";
    private final Path file;
    private final Path backup;

    public LastSessionStore(Path file) {
        this.file = file.toAbsolutePath();
        this.backup = this.file.resolveSibling(this.file.getFileName() + ".backup");
    }

    public static LastSessionStore systemDefault() {
        return new LastSessionStore(defaultPath());
    }

    static Path defaultPath() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.startsWith("mac")) {
            return Path.of(System.getProperty("user.home"), "Library", "Application Support",
                    "FractalUI", FILE_NAME);
        }
        if (osName.startsWith("windows")) {
            String applicationData = firstNonBlank(
                    System.getenv("LOCALAPPDATA"),
                    System.getenv("APPDATA"),
                    System.getProperty("user.home"));
            return Path.of(applicationData, "FractalUI", FILE_NAME);
        }
        String stateHome = firstNonBlank(
                System.getenv("XDG_STATE_HOME"),
                Path.of(System.getProperty("user.home"), ".local", "state").toString());
        return Path.of(stateHome, "fractalui", FILE_NAME);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        throw new IllegalStateException("No application data directory is available");
    }

    /** Loads the primary file, falling back to the previous valid generation. */
    public Optional<FractalScene> load() throws IOException {
        Optional<FractalScene> primary = readValid(file);
        return primary.isPresent() ? primary : readValid(backup);
    }

    /**
     * Writes a complete temporary file before atomically publishing it. The
     * previous valid primary is retained as a recovery generation.
     */
    public void save(FractalScene scene) throws IOException {
        Optional<String> validPrimary = readValidText(file);
        if (validPrimary.isPresent()) {
            atomicWrite(backup, validPrimary.orElseThrow());
        }
        atomicWrite(file, FractalSceneJson.write(scene));
    }

    private static Optional<FractalScene> readValid(Path path) throws IOException {
        Optional<String> text = readValidText(path);
        if (text.isEmpty()) return Optional.empty();
        return Optional.of(FractalSceneJson.read(text.orElseThrow()));
    }

    private static Optional<String> readValidText(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return Optional.empty();
        if (Files.size(path) > 1_048_576L) return Optional.empty();
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            FractalSceneJson.read(text);
            return Optional.of(text);
        } catch (CharacterCodingException | IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static void atomicWrite(Path target, String contents) throws IOException {
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Session file requires a parent directory");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("The session directory does not support atomic replacement", exception);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
