package com.shangin.fractal.app;

import javafx.application.Application;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationModuleTest {
    @Test
    void entryPointDoesNotTriggerTheJavafxSubclassLauncher() throws Exception {
        assertFalse(Application.class.isAssignableFrom(FractalLauncher.class),
                "The entry point must run before the JavaFX toolkit starts");
        var main = FractalLauncher.class.getMethod("main", String[].class);
        assertTrue(java.lang.reflect.Modifier.isStatic(main.getModifiers()));
        assertEquals(void.class, main.getReturnType());
    }

    @Test
    void javafxCanLaunchApplicationWithoutExportingInternalApis() {
        Module application = FractalApplication.class.getModule();
        Module graphics = Application.class.getModule();
        assertTrue(application.isNamed(), "This contract requires the modular test runtime");
        assertTrue(application.isOpen("com.shangin.fractal.app", graphics),
                "JavaFX must be able to construct the application reflectively");
        var exports = application.getDescriptor().exports();
        assertTrue(exports.stream().noneMatch(export ->
                export.source().equals("com.shangin.fractal.app")
                        || export.source().equals("com.shangin.fractal.ui")),
                "Application and UI implementation packages must not expose internal model types");
    }
}
