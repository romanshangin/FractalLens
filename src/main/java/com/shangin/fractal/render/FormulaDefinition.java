package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalFormula;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.scene.FractalScene;

import java.util.Objects;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Backend-neutral identity and immutable parameters of a fractal formula.
 * The factory is consumed by the direct-double backend; other backends can
 * select their own implementation from {@link #id()} and {@link #preset()}.
 */
public final class FormulaDefinition {

    private final String id;
    private final FractalPreset preset;
    private final OrbitTrap orbitTrap;
    private final Map<String, Double> parameters;
    private final Supplier<FractalFormula> factory;
    private final Object equalityKey;

    private FormulaDefinition(
            String id,
            FractalPreset preset,
            OrbitTrap orbitTrap,
            Map<String, Double> parameters,
            Supplier<FractalFormula> factory,
            Object equalityKey
    ) {
        this.id = Objects.requireNonNull(id);
        this.preset = preset;
        this.orbitTrap = Objects.requireNonNull(orbitTrap);
        this.parameters = Map.copyOf(parameters);
        this.factory = Objects.requireNonNull(factory);
        this.equalityKey = Objects.requireNonNull(equalityKey);
    }

    public static FormulaDefinition forPreset(
            FractalPreset preset,
            OrbitTrap orbitTrap
    ) {
        Objects.requireNonNull(preset);
        Map<String, Double> parameters = switch (preset) {
            case JULIA -> Map.of("cReal", -0.8, "cImaginary", 0.156);
            case MULTIBROT_CUBIC -> Map.of("power", 3.0);
            default -> Map.of();
        };
        return new FormulaDefinition(
                preset.name(), preset, orbitTrap, parameters,
                preset::createFormula, preset);
    }

    public static FormulaDefinition forScene(FractalScene scene) {
        Objects.requireNonNull(scene);
        if (scene.fractal() != FractalPreset.JULIA) {
            return forPreset(scene.fractal(), scene.coloring().orbitTrap());
        }
        double real = scene.juliaParameters().real();
        double imaginary = scene.juliaParameters().imaginary();
        return new FormulaDefinition(
                scene.fractal().name(), scene.fractal(), scene.coloring().orbitTrap(),
                Map.of("cReal", real, "cImaginary", imaginary),
                () -> new com.shangin.fractal.formula.JuliaFormula(real, imaginary),
                scene.fractal());
    }

    /** Compatibility factory for tests and extension formulas outside presets. */
    public static FormulaDefinition custom(
            FractalFormula formula,
            OrbitTrap orbitTrap
    ) {
        Objects.requireNonNull(formula);
        return new FormulaDefinition(
                formula.getClass().getName(), null, orbitTrap, Map.of(),
                () -> formula, formula);
    }

    public String id() {
        return id;
    }

    public FractalPreset preset() {
        return preset;
    }

    public OrbitTrap orbitTrap() {
        return orbitTrap;
    }

    public Map<String, Double> parameters() {
        return parameters;
    }

    public FractalCalculator createDirectCalculator() {
        return new FractalCalculator(factory.get(), orbitTrap, this);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FormulaDefinition definition)) {
            return false;
        }
        return id.equals(definition.id)
                && orbitTrap == definition.orbitTrap
                && parameters.equals(definition.parameters)
                && equalityKey == definition.equalityKey;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * id.hashCode() + orbitTrap.hashCode())
                + parameters.hashCode()) + System.identityHashCode(equalityKey);
    }
}
