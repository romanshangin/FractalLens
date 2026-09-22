package com.shangin.fractal.scene;

import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Versioned JSON representation of a reproducible fractal scene. */
public final class FractalSceneJson {

    public static final int CURRENT_SCHEMA_VERSION = 2;
    private static final int MAX_JSON_LENGTH = 1_048_576;
    private static final int MAX_PALETTE_STOPS = 4_096;

    private FractalSceneJson() {}

    public static String write(FractalScene scene) {
        Objects.requireNonNull(scene, "Scene must not be null");
        StringBuilder json = new StringBuilder(768);
        json.append("{\n")
                .append("  \"schemaVersion\": ").append(CURRENT_SCHEMA_VERSION).append(",\n")
                .append("  \"fractal\": ").append(quoted(scene.fractal().name())).append(",\n")
                .append("  \"viewport\": {\n")
                .append("    \"centerReal\": ").append(decimal(scene.viewport().center().real())).append(",\n")
                .append("    \"centerImaginary\": ").append(decimal(scene.viewport().center().imaginary())).append(",\n")
                .append("    \"scale\": ").append(decimal(scene.viewport().scaleExact())).append("\n")
                .append("  },\n")
                .append("  \"iterations\": {\n")
                .append("    \"baseIterations\": ").append(scene.iterations().baseIterations()).append(",\n")
                .append("    \"iterationsPerZoomLevel\": ")
                .append(scene.iterations().iterationsPerZoomLevel()).append("\n")
                .append("  },\n")
                .append("  \"coloring\": {\n")
                .append("    \"palette\": ").append(quoted(scene.coloring().palette().name())).append(",\n")
                .append("    \"paletteStops\": [\n");
        List<ColorStop> stops = scene.coloring().paletteStops();
        for (int index = 0; index < stops.size(); index++) {
            ColorStop stop = stops.get(index);
            json.append("      {\"position\": ").append(Double.toString(stop.position()))
                    .append(", \"color\": ").append(quoted(String.format("#%08X", stop.color())))
                    .append('}');
            if (index + 1 < stops.size()) json.append(',');
            json.append('\n');
        }
        json.append("    ],\n")
                .append("    \"colorScale\": ").append(Double.toString(scene.coloring().colorScale())).append(",\n")
                .append("    \"offset\": ").append(Double.toString(scene.coloring().offset())).append(",\n")
                .append("    \"histogramColoring\": ").append(scene.coloring().histogramColoring()).append(",\n")
                .append("    \"orbitTrap\": ").append(quoted(scene.coloring().orbitTrap().name())).append("\n")
                .append("  },\n")
                .append("  \"antialiasing\": {\n")
                .append("    \"samplingPattern\": ")
                .append(quoted(scene.antialiasing().samplingPattern().name())).append(",\n")
                .append("    \"renderMode\": ")
                .append(quoted(scene.antialiasing().renderMode().name())).append("\n")
                .append("  }\n")
                .append("}\n");
        return json.toString();
    }

    public static FractalScene read(String json) {
        Objects.requireNonNull(json, "JSON must not be null");
        if (json.length() > MAX_JSON_LENGTH) {
            throw new IllegalArgumentException("Scene JSON exceeds the 1 MiB limit");
        }
        try {
            Map<String, Object> root = object(new Parser(json).parse(), "root");
            int version = integer(required(root, "schemaVersion"), "schemaVersion");
            return switch (version) {
                case 1 -> readVersion1(root);
                case CURRENT_SCHEMA_VERSION -> readVersion2(root);
                default -> throw new IllegalArgumentException("Unsupported scene schema version: " + version);
            };
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid scene JSON", exception);
        }
    }

    private static FractalScene readVersion1(Map<String, Object> root) {
        FractalPreset fractal = enumValue(FractalPreset.class,
                string(required(root, "fractal"), "fractal"), "fractal");
        Viewport viewport = viewport(root);
        Map<String, Object> iterationObject = object(required(root, "iterations"), "iterations");
        IterationSettings iterations = new IterationSettings(
                integer(required(iterationObject, "baseIterations"), "baseIterations"),
                integer(required(iterationObject, "iterationsPerZoomLevel"), "iterationsPerZoomLevel"));
        Map<String, Object> coloringObject = object(required(root, "coloring"), "coloring");
        PalettePreset palette = enumValue(PalettePreset.class,
                string(required(coloringObject, "palette"), "palette"), "palette");
        ColoringSettings coloring = new ColoringSettings(
                palette,
                palette.stops(),
                decimalNumber(required(coloringObject, "colorScale"), "colorScale"),
                decimalNumber(required(coloringObject, "offset"), "offset"),
                false,
                OrbitTrap.NONE);
        return new FractalScene(fractal, viewport, iterations, coloring, new AntialiasSettings());
    }

    private static FractalScene readVersion2(Map<String, Object> root) {
        FractalPreset fractal = enumValue(FractalPreset.class,
                string(required(root, "fractal"), "fractal"), "fractal");
        Viewport viewport = viewport(root);
        Map<String, Object> iterationObject = object(required(root, "iterations"), "iterations");
        IterationSettings iterations = new IterationSettings(
                integer(required(iterationObject, "baseIterations"), "baseIterations"),
                integer(required(iterationObject, "iterationsPerZoomLevel"), "iterationsPerZoomLevel"));

        Map<String, Object> coloringObject = object(required(root, "coloring"), "coloring");
        PalettePreset palette = enumValue(PalettePreset.class,
                string(required(coloringObject, "palette"), "palette"), "palette");
        List<Object> serializedStops = array(required(coloringObject, "paletteStops"), "paletteStops");
        if (serializedStops.size() > MAX_PALETTE_STOPS) {
            throw new IllegalArgumentException("Palette stop count exceeds the supported limit");
        }
        List<ColorStop> stops = new ArrayList<>(serializedStops.size());
        for (Object value : serializedStops) {
            Map<String, Object> stop = object(value, "palette stop");
            double position = decimalNumber(required(stop, "position"), "position");
            if (!Double.isFinite(position)) {
                throw new IllegalArgumentException("Palette stop position must be finite");
            }
            stops.add(new ColorStop(position, color(required(stop, "color"))));
        }
        ColoringSettings coloring = new ColoringSettings(
                palette,
                stops,
                decimalNumber(required(coloringObject, "colorScale"), "colorScale"),
                decimalNumber(required(coloringObject, "offset"), "offset"),
                bool(required(coloringObject, "histogramColoring"), "histogramColoring"),
                enumValue(OrbitTrap.class,
                        string(required(coloringObject, "orbitTrap"), "orbitTrap"), "orbitTrap"));

        Map<String, Object> antialiasingObject = object(required(root, "antialiasing"), "antialiasing");
        AntialiasSettings antialiasing = new AntialiasSettings(
                enumValue(SamplingPattern.class,
                        string(required(antialiasingObject, "samplingPattern"), "samplingPattern"),
                        "samplingPattern"),
                enumValue(InteractiveRenderMode.class,
                        string(required(antialiasingObject, "renderMode"), "renderMode"), "renderMode"));
        return new FractalScene(fractal, viewport, iterations, coloring, antialiasing);
    }

    private static Viewport viewport(Map<String, Object> root) {
        Map<String, Object> viewport = object(required(root, "viewport"), "viewport");
        return new Viewport(
                decimalString(required(viewport, "centerReal"), "centerReal"),
                decimalString(required(viewport, "centerImaginary"), "centerImaginary"),
                decimalString(required(viewport, "scale"), "scale"));
    }

    private static Object required(Map<String, Object> object, String name) {
        if (!object.containsKey(name)) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
        return object.get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String name) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        return (List<Object>) list;
    }

    private static String string(Object value, String name) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return text;
    }

    private static String decimalString(Object value, String name) {
        String text = string(value, name);
        try {
            new BigDecimal(text);
            return text;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must contain a decimal value", exception);
        }
    }

    private static int integer(Object value, String name) {
        if (!(value instanceof BigDecimal number)) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        try {
            return number.intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static double decimalNumber(Object value, String name) {
        if (!(value instanceof BigDecimal number)) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return result;
    }

    private static boolean bool(Object value, String name) {
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return result;
    }

    private static int color(Object value) {
        String text = string(value, "color");
        if (!text.matches("#[0-9A-Fa-f]{8}")) {
            throw new IllegalArgumentException("color must use #AARRGGBB format");
        }
        return (int) Long.parseLong(text.substring(1), 16);
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String name) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown " + name + ": " + value, exception);
        }
    }

    private static String decimal(BigDecimal value) {
        return quoted(value.toString());
    }

    private static String quoted(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04X", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static final class Parser {
        private final String source;
        private int index;

        private Parser(String source) {
            this.source = source;
        }

        private Object parse() {
            skipWhitespace();
            Object value = value(0);
            skipWhitespace();
            if (index != source.length()) error("Unexpected trailing content");
            return value;
        }

        private Object value(int depth) {
            if (depth > 32) error("JSON nesting exceeds the supported limit");
            skipWhitespace();
            if (index >= source.length()) error("Expected a value");
            return switch (source.charAt(index)) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) {
            index++;
            skipWhitespace();
            Map<String, Object> result = new LinkedHashMap<>();
            if (take('}')) return result;
            while (true) {
                skipWhitespace();
                if (index >= source.length() || source.charAt(index) != '"') error("Expected an object key");
                String key = string();
                skipWhitespace();
                expect(':');
                if (result.containsKey(key)) error("Duplicate object key: " + key);
                result.put(key, value(depth));
                skipWhitespace();
                if (take('}')) return result;
                expect(',');
            }
        }

        private List<Object> array(int depth) {
            index++;
            skipWhitespace();
            List<Object> result = new ArrayList<>();
            if (take(']')) return result;
            while (true) {
                result.add(value(depth));
                skipWhitespace();
                if (take(']')) return result;
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (index < source.length()) {
                char character = source.charAt(index++);
                if (character == '"') return result.toString();
                if (character == '\\') {
                    if (index >= source.length()) error("Unterminated string escape");
                    char escape = source.charAt(index++);
                    switch (escape) {
                        case '"', '\\', '/' -> result.append(escape);
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> result.append(unicodeEscape());
                        default -> error("Invalid string escape");
                    }
                } else {
                    if (character < 0x20) error("Unescaped control character in string");
                    result.append(character);
                }
            }
            error("Unterminated string");
            return null;
        }

        private char unicodeEscape() {
            if (index + 4 > source.length()) error("Incomplete Unicode escape");
            try {
                char result = (char) Integer.parseInt(source.substring(index, index + 4), 16);
                index += 4;
                return result;
            } catch (NumberFormatException exception) {
                error("Invalid Unicode escape");
                return 0;
            }
        }

        private BigDecimal number() {
            int start = index;
            if (take('-') && index >= source.length()) error("Incomplete number");
            if (take('0')) {
                if (index < source.length() && Character.isDigit(source.charAt(index))) error("Leading zero in number");
            } else {
                digits();
            }
            if (take('.')) digits();
            if (index < source.length() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                index++;
                if (index < source.length() && (source.charAt(index) == '+' || source.charAt(index) == '-')) index++;
                digits();
            }
            try {
                return new BigDecimal(source.substring(start, index));
            } catch (NumberFormatException exception) {
                error("Invalid number");
                return null;
            }
        }

        private void digits() {
            int start = index;
            while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
            if (start == index) error("Expected a digit");
        }

        private Object literal(String literal, Object value) {
            if (!source.startsWith(literal, index)) error("Invalid value");
            index += literal.length();
            return value;
        }

        private void skipWhitespace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        }

        private void expect(char expected) {
            if (!take(expected)) error("Expected '" + expected + "'");
        }

        private boolean take(char expected) {
            if (index < source.length() && source.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void error(String message) {
            throw new IllegalArgumentException(message + " at offset " + index);
        }
    }
}
