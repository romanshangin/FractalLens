package com.shangin.fractal.app;

import javafx.application.Application;
import com.shangin.fractal.ui.PaletteRecolorBenchmark;
import com.shangin.fractal.ui.InteractionLatencyBenchmark;
import com.shangin.fractal.ui.GpuRenderBenchmark;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationModuleTest {
    @Test
    void entryPointDoesNotTriggerTheJavafxSubclassLauncher() throws Exception {
        for (Class<?> entryPoint : new Class<?>[]{FractalLauncher.class, FractalApplication.class,
                PaletteRecolorBenchmark.class, InteractionLatencyBenchmark.class,
                GpuRenderBenchmark.class, ColdStartupBenchmark.class}) {
            assertFalse(Application.class.isAssignableFrom(entryPoint),
                    () -> entryPoint.getName() + " must run before the JavaFX toolkit starts");
            var main = entryPoint.getMethod("main", String[].class);
            assertTrue(java.lang.reflect.Modifier.isStatic(main.getModifiers()));
            assertEquals(void.class, main.getReturnType());
        }
    }

    @Test
    void javafxCanLaunchApplicationWithoutExportingInternalApis() {
        Module application = FractalApplication.class.getModule();
        Module graphics = Application.class.getModule();
        assertTrue(application.isNamed(), "This contract requires the modular test runtime");
        assertTrue(application.isOpen("com.shangin.fractal.app", graphics),
                "JavaFX must be able to construct the application reflectively");
        assertTrue(application.isOpen("com.shangin.fractal.ui", graphics),
                "JavaFX must be able to construct benchmark lifecycle adapters reflectively");
        var exports = application.getDescriptor().exports();
        assertTrue(exports.stream().noneMatch(export ->
                export.source().equals("com.shangin.fractal.app")
                        || export.source().equals("com.shangin.fractal.ui")),
                "Application and UI implementation packages must not expose internal model types");
    }
}
