package com.shangin.fractal.ui;

import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.FractalScene;

/** Test-only fixture setup. Reflection is confined here; measured navigation uses installed handlers. */
public final class BaselineInputAccess {
    private BaselineInputAccess() {}

    public static void seed(FractalView view, FractalScene scene) throws Exception {
        if (!view.isLatencyIdle()) throw new IllegalStateException("Seed requires idle view");
        FractalCamera camera = field(view, "camera");
        set(camera, "viewport", scene.viewport());
        set(view, "scene", scene);
        var recalculate = FractalView.class.getDeclaredMethod("recalculate");
        recalculate.setAccessible(true);
        recalculate.invoke(view);
    }

    public static boolean idle(FractalView view) { return view.isLatencyIdle() && view.hasCompletedFrame(); }
    public static FractalSurface surface(FractalView view) throws Exception { return field(view, "fractalSurface"); }
    public static Viewport viewport(FractalView view) throws Exception { return ((FractalCamera) field(view, "camera")).viewport(); }

    /** Mirrors the controller's deep zoom policy for the visible completed frame. */
    public static boolean deepZoom(FractalView view) throws Exception {
        var completed = surface(view).completedRender();
        var fractal = completed.scene().fractal();
        return fractal.supportsDeepZoom() && !fractal.hasSufficientDirectPrecision(
                completed.scene().viewport(), completed.frame().job().width(), completed.frame().job().height());
    }
    public static boolean deepAa(FractalView view) throws Exception {
        return surface(view).completedRender().scene().antialiasing().deepZoomRenderMode().antialiases();
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(object);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
