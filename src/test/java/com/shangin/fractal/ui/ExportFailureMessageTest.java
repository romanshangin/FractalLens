package com.shangin.fractal.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ExportFailureMessageTest {

    private static final Path FOLDER = Path.of("exports").toAbsolutePath();
    private static final Path TARGET = FOLDER.resolve("julia.png");
    private static final String STAGING = FOLDER.resolve(".fractallens-export-123.png").toString();

    @Test
    void accessDeniedNamesChosenFileAndFolderWithoutStagingPath() {
        String message = MainView.exportFailureMessage(TARGET, new AccessDeniedException(STAGING));

        assertEquals("The PNG image could not be saved as \"julia.png\". FractalLens does not have "
                + "permission to write to the folder \"" + FOLDER + "\". Choose another folder.", message);
        assertFalse(message.contains(".fractallens-export"));
    }

    @Test
    void missingFolderIsDescribedByChosenFolder() {
        assertEquals("The PNG image could not be saved as \"julia.png\". The folder \"" + FOLDER
                        + "\" does not exist.",
                MainView.exportFailureMessage(TARGET, new NoSuchFileException(STAGING)));
    }

    @Test
    void fileSystemReasonIsShownWithoutItsPaths() {
        String message = MainView.exportFailureMessage(TARGET, new AtomicMoveNotSupportedException(
                STAGING, TARGET.toString(), "atomic replacement is not supported"));

        assertEquals("The PNG image could not be saved as \"julia.png\". "
                + "Atomic replacement is not supported.", message);
    }

    @Test
    void fileSystemErrorWithoutReasonUsesGenericSentence() {
        assertEquals("The PNG image could not be saved as \"julia.png\". A file system error occurred.",
                MainView.exportFailureMessage(TARGET,
                        new java.nio.file.FileSystemException(STAGING)));
    }

    @Test
    void uncheckedWrapperIsReportedByItsCause() {
        assertEquals(MainView.exportFailureMessage(TARGET, new AccessDeniedException(STAGING)),
                MainView.exportFailureMessage(TARGET,
                        new UncheckedIOException(new AccessDeniedException(STAGING))));
    }

    @Test
    void otherFailuresKeepTheirMessageOrFallBack() {
        assertEquals("The PNG image could not be saved as \"julia.png\". No space left on device.",
                MainView.exportFailureMessage(TARGET, new IOException("no space left on device")));
        assertEquals("The PNG image could not be saved as \"julia.png\". An unexpected error occurred.",
                MainView.exportFailureMessage(TARGET, new IllegalStateException()));
    }
}
