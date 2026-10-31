package com.shangin.fractal.ui;

import com.shangin.fractal.app.BookmarkStore;
import com.shangin.fractal.render.RenderTarget;
import com.shangin.fractal.export.AdaptivePngExportService;

import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.IterationSettings;
import com.shangin.fractal.scene.JuliaParameters;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.Alert;
import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import javafx.css.PseudoClass;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.Map;
import java.util.IdentityHashMap;
import javafx.stage.Modality;

/** Focused editors reached from the menu bar; none occupies canvas space. */
final class FractalDialogs {
    private FractalDialogs() {}

    private static final Map<Window, MacNoticeSheet> HELP = new IdentityHashMap<>();
    private static final Map<Window, Dialog<Void>> HELP_FALLBACK = new IdentityHashMap<>();
    private static final Map<Window, Runnable> PALETTES = new IdentityHashMap<>();

    private static boolean nativeDialog(Runnable show) {
        if (!MacParameterSheet.isAvailable()) return false;
        try {
            show.run();
            return true;
        } catch (LinkageError | RuntimeException error) {
            System.getLogger(FractalDialogs.class.getName()).log(System.Logger.Level.WARNING,
                    "Native dialog unavailable; using JavaFX dialog", error);
            return false;
        }
    }

    /** The native sheet's single validation message; the JavaFX dialog names the failing rule. */
    static final String COORDINATE_ERROR = "Enter numbers for the coordinates and a zoom of at least 1 "
            + "that this fractal can render precisely. Scientific notation, such as 1.5e-12, is supported.";
    private static final String COORDINATE_NUMBER_ERROR =
            "Enter a number for each coordinate. Scientific notation, such as 1.5e-12, is supported.";
    private static final String ZOOM_NUMBER_ERROR =
            "Enter a zoom of at least 1. Scientific notation, such as 1e30, is supported.";
    private static final String ZOOM_PRECISION_ERROR = "This fractal cannot render this zoom precisely. "
            + "Enter a smaller zoom; deep zoom is available for Mandelbrot and Julia.";

    /**
     * Go to Coordinates. Both editors apply through {@link CoordinateEditor#submit} while
     * still open, so a failed Go keeps the draft and a successful one applies exactly once.
     */
    @SuppressWarnings("resource") // ACTIVE owns the asynchronous sheet until completion or owner closure.
    static void coordinates(Window owner, CoordinateText shown, boolean deepZoom, CoordinateEditor editor) {
        if (MacParameterSheet.hasOpenSheet(owner)) return;
        if (!nativeDialog(() -> MacFormSheet.coordinates(owner, shown, coordinateHint(deepZoom), editor)))
            coordinateDialog(owner, shown, deepZoom, editor).showAndWait();
    }

    static String coordinateHint(boolean deepZoom) {
        return "Enter the center and the zoom relative to the initial view."
                + (deepZoom ? " High-precision deep zoom is active." : "");
    }

    static void palette(Window owner, List<ColorStop> stops, Consumer<List<ColorStop>> apply) {
        if (MacParameterSheet.hasOpenSheet(owner) || PALETTES.containsKey(owner)) return;
        if (!nativeDialog(() -> {
            var sheet = MacPaletteSheet.show(owner, stops, apply);
            PALETTES.put(owner, sheet::close);
            sheet.onClosed(() -> PALETTES.remove(owner));
        })) {
            var dialog = paletteDialog(owner, stops);
            PALETTES.put(owner, dialog::close);
            dialog.setOnHidden(event -> {
                PALETTES.remove(owner);
                if (owner.isShowing() && dialog.getResult() != null) apply.accept(dialog.getResult());
            });
            dialog.show();
        }
    }

    @SuppressWarnings("resource") // ACTIVE owns the asynchronous sheet until completion or owner closure.
    static void exportSize(Window owner, RenderTarget target, Consumer<RenderTarget> apply) {
        if (MacParameterSheet.hasOpenSheet(owner)) return;
        if (!nativeDialog(() -> MacFormSheet.export(owner, target, apply)))
            exportSizeDialog(owner, target).showAndWait().ifPresent(apply);
    }

    @SuppressWarnings("resource") // ACTIVE owns the asynchronous alert until completion or owner closure.
    static void message(Window owner, String title, String detail) {
        MacParameterSheet.whenAvailable(owner, () -> {
            if (!nativeDialog(() -> MacNoticeSheet.show(owner, title, detail, false, false, ignored -> {})))
                messageDialog(owner, Alert.AlertType.ERROR, title, title, detail).show();
        });
    }

    @SuppressWarnings("resource") // ACTIVE owns the asynchronous alert until completion or owner closure.
    static void confirmExportClose(Window owner, Consumer<Boolean> result) {
        MacParameterSheet.whenAvailable(owner, () -> {
            if (!nativeDialog(() -> MacNoticeSheet.show(owner, "Close FractalLens?",
                    "A PNG export is still in progress. Closing will cancel it.", true, false, result))) {
                var dialog = exportCloseDialog(owner);
                dialog.setOnHidden(event -> result.accept(dialog.getResult() != null
                        && dialog.getResult().getButtonData() == ButtonData.YES));
                dialog.show();
            }
        });
    }

    static Dialog<RenderTarget> exportSizeDialog(
            Window owner, RenderTarget current) {
        Dialog<RenderTarget> dialog = new Dialog<>();
        dialog.setTitle("Export PNG");
        TextField width = new TextField(Integer.toString(current.width()));
        TextField height = new TextField(Integer.toString(current.height()));
        width.setId("export-width");
        height.setId("export-height");
        GridPane fields = new GridPane(12, 12);
        fields.addRow(0, label("Width (pixels)", width), width);
        fields.addRow(1, label("Height (pixels)", height), height);
        Label hint = new Label("Render a scene snapshot at independent dimensions. "
                + "The center and vertical span stay fixed; width changes the horizontal span. "
                + "Adaptive antialiasing is included. Cancel Export PNG stops the export.");
        hint.setWrapText(true);
        hint.getStyleClass().add("dialog-hint");
        Label error = errorLabel();
        Label estimate = new Label();
        estimate.setId("export-size-estimate");
        estimate.setWrapText(true);
        estimate.getStyleClass().add("dialog-hint");
        Runnable updateEstimate = () -> {
            try {
                RenderTarget target = parseExportTarget(width, height);
                double mib = ((long) target.width() * target.height() * 4.0 + target.height()) / (1024 * 1024);
                estimate.setText(String.format(java.util.Locale.ROOT,
                        "PNG size: up to about %.1f MiB before compression. "
                        + "The saved file is usually smaller; its size depends on image detail.", mib));
            } catch (IllegalArgumentException exception) {
                estimate.setText("Enter valid dimensions to estimate the PNG size.");
            }
        };
        width.textProperty().addListener(ignored -> updateEstimate.run());
        height.textProperty().addListener(ignored -> updateEstimate.run());
        updateEstimate.run();
        VBox content = new VBox(14, hint, fields, estimate, error);
        content.getStyleClass().add("dialog-body");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(480);
        ButtonType export = new ButtonType("Export…", ButtonData.OK_DONE);
        DialogAppearance.install(dialog, owner, () -> width);
        Button confirm = DialogAppearance.buttons(dialog, export, ButtonType.CANCEL);
        confirm.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                var target = parseExportTarget(width, height);
                AdaptivePngExportService.validateTarget(target);
            } catch (IllegalArgumentException exception) {
                showError(dialog, error, exception.getMessage());
                event.consume();
            }
        });
        width.setOnAction(event -> { confirm.fire(); event.consume(); });
        height.setOnAction(event -> { confirm.fire(); event.consume(); });
        dialog.setResultConverter(button -> button == export ? parseExportTarget(width, height) : null);
        return dialog;
    }

    private static RenderTarget parseExportTarget(
            TextField width, TextField height) {
        try {
            return new RenderTarget(
                    Integer.parseInt(width.getText().trim()), Integer.parseInt(height.getText().trim()));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Width and height must be whole numbers of at least 2");
        }
    }

    /** The JavaFX Go to Coordinates editor; its result is Go after an applied or no-op Go, else empty. */
    static Dialog<ButtonType> coordinateDialog(Window owner, CoordinateText shown, boolean deepZoom,
                                               CoordinateEditor editor) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Go to Coordinates");
        TextField real = new TextField(shown.real());
        TextField imaginary = new TextField(shown.imaginary());
        TextField scale = new TextField(shown.zoom());
        real.setId("coordinate-real");
        imaginary.setId("coordinate-imaginary");
        scale.setId("coordinate-zoom");
        real.setPromptText("For example, -0.75");
        imaginary.setPromptText("For example, 0");
        scale.setPromptText("For example, 1e12");
        GridPane fields = new GridPane(12, 12);
        fields.addRow(0, label("Real", real), real);
        fields.addRow(1, label("Imaginary", imaginary), imaginary);
        fields.addRow(2, label("Zoom ×", scale), scale);
        List<TextField> inputs = List.of(real, imaginary, scale);
        for (Node field : inputs) {
            GridPane.setHgrow(field, Priority.ALWAYS);
        }
        Label hint = new Label(coordinateHint(deepZoom));
        hint.setWrapText(true);
        hint.getStyleClass().add("dialog-hint");
        Label error = errorLabel();
        VBox content = new VBox(14, hint, fields, error);
        content.getStyleClass().add("dialog-body");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(480);
        ButtonType go = new ButtonType("Go", ButtonData.OK_DONE);
        DialogAppearance.install(dialog, owner, () -> real);
        Button confirm = DialogAppearance.buttons(dialog, go, ButtonType.CANCEL);
        // Go applies here, before the dialog closes; a failure keeps it open with the draft.
        confirm.addEventFilter(ActionEvent.ACTION, event -> {
            int invalid = editor.submit(shown, real.getText(), imaginary.getText(), scale.getText());
            if (!showCoordinateValidation(dialog, inputs, invalid, error, true)) event.consume();
        });
        for (TextField field : inputs) {
            field.setOnAction(event -> { confirm.fire(); event.consume(); });
            field.textProperty().addListener(ignored -> {
                if (error.isVisible()) showCoordinateValidation(dialog, inputs,
                        editor.invalidField(shown, real.getText(), imaginary.getText(), scale.getText()),
                        error, false);
            });
        }
        dialog.setResultConverter(button -> button == go ? go : null);
        return dialog;
    }

    static Optional<JuliaParameters> juliaParameters(Window owner, JuliaParameters current) {
        return juliaParametersDialog(owner, current).showAndWait();
    }

    static Optional<IterationSettings> iterationSettings(Window owner, IterationSettings current) {
        return iterationSettingsDialog(owner, current).showAndWait();
    }

    static Dialog<IterationSettings> iterationSettingsDialog(Window owner, IterationSettings current) {
        Dialog<IterationSettings> dialog = new Dialog<>();
        dialog.setTitle("Iteration Settings");
        TextField base = new TextField(Integer.toString(current.baseIterations()));
        TextField zoom = new TextField(Integer.toString(current.iterationsPerZoomLevel()));
        base.setId("iteration-base");
        zoom.setId("iteration-zoom");
        base.setPromptText(Integer.toString(IterationSettings.DEFAULT_BASE_ITERATIONS));
        zoom.setPromptText(Integer.toString(IterationSettings.DEFAULT_ITERATIONS_PER_ZOOM_LEVEL));
        GridPane fields = new GridPane(12, 12);
        fields.addRow(0, label("Base iterations", base), base);
        fields.addRow(1, label("Additional per zoom level", zoom), zoom);
        GridPane.setHgrow(base, Priority.ALWAYS);
        GridPane.setHgrow(zoom, Priority.ALWAYS);
        Label hint = new Label("Set the base render budget and how much it grows as you zoom in.");
        hint.setWrapText(true);
        hint.getStyleClass().add("dialog-hint");
        Label error = errorLabel();
        VBox content = new VBox(14, hint, fields, error);
        content.getStyleClass().add("dialog-body");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(480);
        ButtonType reset = new ButtonType("Reset to Defaults", ButtonData.OTHER);
        ButtonType apply = new ButtonType("Apply", ButtonData.OK_DONE);
        DialogAppearance.install(dialog, owner, () -> base);
        Button confirm = DialogAppearance.buttons(dialog, apply, reset, ButtonType.CANCEL);
        Button resetButton = (Button) dialog.getDialogPane().lookupButton(reset);
        resetButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            base.setText(Integer.toString(IterationSettings.DEFAULT_BASE_ITERATIONS));
            zoom.setText(Integer.toString(IterationSettings.DEFAULT_ITERATIONS_PER_ZOOM_LEVEL));
            validateIterationSettings(dialog, base, zoom, error, false);
            base.requestFocus();
            base.selectAll();
        });
        confirm.addEventFilter(ActionEvent.ACTION, event -> {
            if (!validateIterationSettings(dialog, base, zoom, error, true)) event.consume();
        });
        base.setOnAction(event -> { confirm.fire(); event.consume(); });
        zoom.setOnAction(event -> { confirm.fire(); event.consume(); });
        for (TextField field : List.of(base, zoom)) {
            field.textProperty().addListener(ignored -> {
                if (error.isVisible()) validateIterationSettings(dialog, base, zoom, error, false);
            });
        }
        dialog.setResultConverter(button -> button == apply
                ? new IterationSettings(Integer.parseInt(base.getText().trim()),
                        Integer.parseInt(zoom.getText().trim())) : null);
        return dialog;
    }

    static Optional<AntialiasSettings> settings(Window owner, AntialiasSettings current) {
        return settingsDialog(owner, current).showAndWait();
    }

    static Dialog<AntialiasSettings> settingsDialog(Window owner, AntialiasSettings current) {
        Dialog<AntialiasSettings> dialog = new Dialog<>();
        dialog.setTitle(SettingsWindow.TITLE);
        Label hint = new Label(SettingsWindow.HINT);
        hint.setWrapText(true);
        hint.getStyleClass().add("dialog-hint");
        VBox content = new VBox(10, hint);
        List<ComboBox<String>> choices = new ArrayList<>();
        GridPane section = null;
        String sectionTitle = null;
        for (SettingsWindow.ChoiceField field : SettingsWindow.fields(current)) {
            if (!field.section().equals(sectionTitle)) {
                sectionTitle = field.section();
                section = new GridPane(12, 4);
                Label title = new Label(sectionTitle);
                title.getStyleClass().add("dialog-section");
                VBox.setMargin(title, new javafx.geometry.Insets(8, 0, 0, 0));
                content.getChildren().addAll(title, section);
            }
            ComboBox<String> choice = new ComboBox<>(FXCollections.observableArrayList(field.choices()));
            choice.setId(field.id());
            choice.getSelectionModel().select(field.selected());
            choice.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHgrow(choice, Priority.ALWAYS);
            Label fieldHint = new Label(field.hint());
            fieldHint.setWrapText(true);
            fieldHint.getStyleClass().add("dialog-hint");
            Label fieldLabel = label(field.label(), choice);
            // The wrapping hint shares the grid; keep the label column from collapsing.
            fieldLabel.setMinWidth(Region.USE_PREF_SIZE);
            int row = section.getRowCount();
            section.addRow(row, fieldLabel, choice);
            section.add(fieldHint, 1, row + 1);
            GridPane.setMargin(fieldHint, new javafx.geometry.Insets(0, 0, 8, 0));
            choices.add(choice);
        }
        content.getStyleClass().add("dialog-body");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(520);
        ButtonType reset = new ButtonType(SettingsWindow.RESET_TITLE, ButtonData.OTHER);
        ButtonType apply = new ButtonType("Apply", ButtonData.OK_DONE);
        DialogAppearance.install(dialog, owner, choices::getFirst);
        DialogAppearance.buttons(dialog, apply, reset, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(reset).addEventFilter(ActionEvent.ACTION, event -> {
            // Reset only changes the draft; Apply still decides whether the scene changes.
            event.consume();
            int[] defaults = SettingsWindow.defaultSelection();
            for (int index = 0; index < choices.size(); index++) {
                choices.get(index).getSelectionModel().select(defaults[index]);
            }
            choices.getFirst().requestFocus();
        });
        dialog.setResultConverter(button -> button == apply
                ? SettingsWindow.selection(choices.stream()
                        .mapToInt(choice -> choice.getSelectionModel().getSelectedIndex()).toArray())
                : null);
        return dialog;
    }

    static Dialog<JuliaParameters> juliaParametersDialog(Window owner, JuliaParameters current) {
        Dialog<JuliaParameters> dialog = new Dialog<>();
        dialog.setTitle("Julia Parameters");
        TextField real = new TextField(Double.toString(current.real()));
        TextField imaginary = new TextField(Double.toString(current.imaginary()));
        real.setId("julia-real");
        imaginary.setId("julia-imaginary");
        GridPane fields = new GridPane(12, 12);
        fields.addRow(0, label("Real", real), real);
        fields.addRow(1, label("Imaginary", imaginary), imaginary);
        GridPane.setHgrow(real, Priority.ALWAYS);
        GridPane.setHgrow(imaginary, Priority.ALWAYS);
        Label error = errorLabel();
        VBox content = new VBox(14, fields, error);
        content.getStyleClass().add("dialog-body");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(480);
        ButtonType reset = new ButtonType("Reset to Defaults", ButtonData.OTHER);
        ButtonType apply = new ButtonType("Apply", ButtonData.OK_DONE);
        DialogAppearance.install(dialog, owner, () -> real);
        Button confirm = DialogAppearance.buttons(dialog, apply, reset, ButtonType.CANCEL);
        Button resetButton = (Button) dialog.getDialogPane().lookupButton(reset);
        resetButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            JuliaParameters defaults = new JuliaParameters();
            real.setText(Double.toString(defaults.real()));
            imaginary.setText(Double.toString(defaults.imaginary()));
            validateJuliaParameters(dialog, real, imaginary, error, false);
            real.requestFocus();
            real.selectAll();
        });
        confirm.addEventFilter(ActionEvent.ACTION, event -> {
            if (!validateJuliaParameters(dialog, real, imaginary, error, true)) event.consume();
        });
        real.setOnAction(event -> { confirm.fire(); event.consume(); });
        imaginary.setOnAction(event -> { confirm.fire(); event.consume(); });
        for (TextField field : List.of(real, imaginary)) {
            field.textProperty().addListener(ignored -> {
                if (error.isVisible()) validateJuliaParameters(dialog, real, imaginary, error, false);
            });
        }
        dialog.setResultConverter(button -> button == apply
                ? new JuliaParameters(Double.parseDouble(real.getText().trim()),
                        Double.parseDouble(imaginary.getText().trim())) : null);
        return dialog;
    }

    static Optional<List<ColorStop>> palette(Window owner, List<ColorStop> stops) {
        return paletteDialog(owner, stops).showAndWait();
    }

    static Dialog<List<ColorStop>> paletteDialog(Window owner, List<ColorStop> stops) {
        Dialog<List<ColorStop>> dialog = new Dialog<>();
        dialog.setTitle("Edit Palette");
        var preview = new javafx.scene.image.ImageView();
        preview.setId("palette-preview");
        preview.setFitWidth(400);
        preview.setFitHeight(28);
        Consumer<List<ColorStop>> updatePreview = values -> {
            var palette = com.shangin.fractal.coloring.GradientPalette.cached(values);
            var image = new javafx.scene.image.WritableImage(400, 1);
            for (int i = 0; i < 400; i++) image.getPixelWriter().setArgb(i, 0, palette.color(i / 399.0));
            preview.setImage(image);
        };
        updatePreview.accept(stops);
        PaletteStopEditor editor = new PaletteStopEditor(stops, ignored -> {});
        ScrollPane scroll = new ScrollPane(editor);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(Math.min(280, Math.max(150, stops.size() * 46 + 42)));
        Label hint = new Label("Choose colors and their positions along the gradient, from 0 to 1.");
        hint.setWrapText(true);
        hint.getStyleClass().add("dialog-hint");
        Label error = errorLabel();
        VBox content = new VBox(14, hint, preview, scroll, error);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        content.getStyleClass().add("dialog-body");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(460);
        ButtonType apply = new ButtonType("Apply", ButtonData.OK_DONE);
        DialogAppearance.install(dialog, owner, editor::initialFocus);
        Button confirm = DialogAppearance.buttons(dialog, apply, ButtonType.CANCEL);
        editor.setOnConfirm(confirm::fire);
        confirm.addEventFilter(ActionEvent.ACTION, event -> {
            TextField invalid = editor.commitEdits();
            if (invalid != null) {
                event.consume();
                showError(dialog, error, "Enter a position from 0 to 1 for every color stop.");
                invalid.requestFocus();
                invalid.selectAll();
            }
        });
        editor.setOnValidationChanged(() -> {
            if (editor.hasValidEdits()) {
                updatePreview.accept(editor.previewStops());
                if (error.isVisible()) clearError(dialog, error);
            }
        });
        dialog.setResultConverter(button -> button == apply ? editor.getStops() : null);
        dialog.initModality(Modality.NONE);
        return dialog;
    }

    static final String HELP_TEXT = "Explore the fractal\n\n"
                + "Drag to move. Use arrow keys to pan, Page Up and Page Down to zoom, and Home to reset the view.\n"
                + "Scroll with a mouse to zoom toward the pointer.\n"
                + "On a trackpad, pinch to zoom and scroll with two fingers to pan.\n\n"
                + "Use View to zoom, reset the view, enter coordinates and zoom, or go full screen.\n"
                + "Choose a formula in Fractal, then use Go to for interesting places in that fractal.\n"
                + "Choose a palette in Color.\n"
                + "Edit iteration settings in Render. For Julia, edit both constant components there; Reset to Defaults restores -0.8 and 0.156 before Apply.\n"
                + "Animate Palette is available when Histogram Coloring and Orbit Trap are off.\n\n"
                + "Use File → Export PNG… to save the rendered image.\n\n"
                + "Render status\n\n"
                + "The badge in the bottom-left corner shows the rendering mode and the state of the current frame. "
                + "Hover over it to see the zoom and the iteration limit.\n"
                + "Standard (blue dot): standard-precision rendering.\n"
                + "Deep Zoom (coral dot): high-precision rendering for deep magnification "
                + "of Mandelbrot or Julia.\n"
                + "Waiting: no frame has been rendered yet.\n"
                + "Rendering N%: the frame is being calculated. "
                + "The percentage covers the whole frame, including any antialiasing pass.\n"
                + "Complete and a time: the frame, including antialiasing, finished in the time shown.\n"
                + "Interrupted: the render was cancelled before it finished.\n"
                + "Failed: the render stopped with an error.\n"
                + "During PNG export the badge also shows the export progress and its result.\n\n"
                + "Render Settings\n\n"
                + "Use File → Settings… to choose how frames are presented, "
                + "separately for standard and deep zoom:\n"
                + "Base only: only the base render is calculated; antialiasing is off.\n"
                + "Base, then antialiasing: the base render is shown first, "
                + "then an antialiasing pass smooths it.\n"
                + "Antialiased display: only finished antialiased tiles are displayed.\n"
                + "Deep zoom antialiasing is precise and can take much longer than the base render.";

    static void close(Window owner) {
        var help = HELP.remove(owner);
        if (help != null) help.close();
        var fallbackHelp = HELP_FALLBACK.remove(owner);
        if (fallbackHelp != null) fallbackHelp.close();
        var palette = PALETTES.remove(owner);
        if (palette != null) palette.run();
        MacParameterSheet.closeOwner(owner);
    }

    @SuppressWarnings("resource") // HELP retains the panel; onClosed removes an already disposed session.
    static void help(Window owner) {
        var existing = HELP.get(owner);
        if (existing != null && existing.isOpen() || HELP_FALLBACK.containsKey(owner)) return;
        if (!nativeDialog(() -> {
            var help = MacNoticeSheet.show(owner, "FractalLens Help", HELP_TEXT, false, true, ignored -> {});
            HELP.put(owner, help);
            help.onClosed(() -> HELP.remove(owner));
        })) {
            var dialog = helpDialog(owner);
            HELP_FALLBACK.put(owner, dialog);
            dialog.setOnHidden(event -> HELP_FALLBACK.remove(owner));
            dialog.show();
        }
    }

    static Dialog<Void> helpDialog(Window owner) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("FractalLens Help");
        Label content = new Label(HELP_TEXT);
        content.setWrapText(true);
        content.getStyleClass().add("dialog-body");
        // Like the native panel, the text scrolls instead of growing past the screen.
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(420);
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().setPrefWidth(520);
        DialogAppearance.install(dialog, owner, () -> dialog.getDialogPane().lookupButton(ButtonType.CLOSE));
        DialogAppearance.buttons(dialog, ButtonType.CLOSE);
        dialog.initModality(Modality.NONE);
        return dialog;
    }

    static Dialog<String> bookmarkNameDialog(Window owner) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Add Bookmark");
        dialog.setHeaderText("Save the current scene");
        TextField name = new TextField();
        name.setId("bookmark-name");
        name.setPromptText("Bookmark name (existing names are replaced)");
        Label error = errorLabel();
        VBox content = new VBox(14, name, error);
        content.getStyleClass().add("dialog-body");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(480);
        ButtonType save = new ButtonType("Save", ButtonData.OK_DONE);
        DialogAppearance.install(dialog, owner, () -> name);
        Button confirm = DialogAppearance.buttons(dialog, save, ButtonType.CANCEL);
        confirm.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                BookmarkStore.validateName(name.getText());
                clearError(dialog, error);
            } catch (IllegalArgumentException exception) {
                showError(dialog, error, exception.getMessage());
                name.requestFocus();
                event.consume();
            }
        });
        name.setOnAction(event -> { confirm.fire(); event.consume(); });
        dialog.setResultConverter(button -> button == save
                ? BookmarkStore.validateName(name.getText()) : null);
        return dialog;
    }

    static Alert exportCloseDialog(Window owner) {
        ButtonType close = new ButtonType("Yes, Close", ButtonData.YES);
        ButtonType stay = new ButtonType("No, Return", ButtonData.CANCEL_CLOSE);
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("Export in progress");
        dialog.setHeaderText("Close FractalLens?");
        dialog.setContentText("A PNG export is still in progress. Closing will cancel it.");
        dialog.getDialogPane().setPrefWidth(480);
        DialogAppearance.install(dialog, owner, () -> dialog.getDialogPane().lookupButton(stay));
        DialogAppearance.buttons(dialog, stay, close);
        return dialog;
    }

    static Alert messageDialog(Window owner, Alert.AlertType type, String title, String header, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(message);
        alert.getDialogPane().setPrefWidth(480);
        DialogAppearance.install(alert, owner, () -> alert.getDialogPane().lookupButton(ButtonType.OK));
        DialogAppearance.buttons(alert, ButtonType.OK);
        return alert;
    }

    private static Label errorLabel() {
        Label error = new Label();
        error.setId("dialog-validation-error");
        error.getStyleClass().add("dialog-error");
        error.setWrapText(true);
        error.setMinHeight(Region.USE_PREF_SIZE);
        error.setVisible(false);
        error.setManaged(false);
        return error;
    }

    private static boolean showCoordinateValidation(Dialog<?> dialog, List<TextField> fields, int invalidIndex,
                                                    Label error, boolean focus) {
        for (int i = 0; i < fields.size(); i++) {
            boolean invalid = i + 1 == invalidIndex;
            fields.get(i).pseudoClassStateChanged(PseudoClass.getPseudoClass("invalid"), invalid);
            fields.get(i).setAccessibleHelp(invalid ? (i < 2
                    ? "Enter a number; scientific notation is supported."
                    : "Enter a zoom of at least 1 that this fractal can render precisely.") : null);
        }
        if (invalidIndex == 0) {
            clearError(dialog, error);
            return true;
        }
        showError(dialog, error, invalidIndex < 3 ? COORDINATE_NUMBER_ERROR
                : CoordinateTarget.isZoom(fields.get(2).getText()) ? ZOOM_PRECISION_ERROR : ZOOM_NUMBER_ERROR);
        TextField firstInvalid = fields.get(invalidIndex - 1);
        if (focus) { firstInvalid.requestFocus(); firstInvalid.selectAll(); }
        return false;
    }

    private static boolean validateJuliaParameters(Dialog<?> dialog, TextField real, TextField imaginary,
                                                    Label error, boolean focus) {
        TextField firstInvalid = null;
        for (TextField field : List.of(real, imaginary)) {
            boolean invalid = !isFiniteDouble(field.getText());
            field.pseudoClassStateChanged(PseudoClass.getPseudoClass("invalid"), invalid);
            field.setAccessibleHelp(invalid ? "Enter a finite number." : null);
            if (invalid && firstInvalid == null) firstInvalid = field;
        }
        if (firstInvalid == null) {
            clearError(dialog, error);
            return true;
        }
        showError(dialog, error, "Enter finite real and imaginary values.");
        if (focus) { firstInvalid.requestFocus(); firstInvalid.selectAll(); }
        return false;
    }

    private static boolean validateIterationSettings(Dialog<?> dialog, TextField base, TextField zoom,
                                                     Label error, boolean focus) {
        TextField firstInvalid = null;
        boolean invalidBase = !isPositiveInteger(base.getText());
        boolean invalidZoom = !isNonNegativeInteger(zoom.getText());
        for (TextField field : List.of(base, zoom)) {
            boolean invalid = field == base ? invalidBase : invalidZoom;
            field.pseudoClassStateChanged(PseudoClass.getPseudoClass("invalid"), invalid);
            field.setAccessibleHelp(invalid ? iterationHelp(field == base) : null);
            if (invalid && firstInvalid == null) firstInvalid = field;
        }
        if (firstInvalid == null) {
            clearError(dialog, error);
            return true;
        }
        showError(dialog, error, "Enter a positive base count and a non-negative zoom increment.");
        if (focus) { firstInvalid.requestFocus(); firstInvalid.selectAll(); }
        return false;
    }

    private static String iterationHelp(boolean base) {
        return base ? "Enter a positive whole number." : "Enter zero or a positive whole number.";
    }

    private static boolean isFiniteDouble(String text) {
        try {
            return Double.isFinite(Double.parseDouble(text.trim()));
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static void showError(Dialog<?> dialog, Label error, String message) {
        boolean changed = !error.isVisible();
        error.setText(message);
        error.setVisible(true);
        error.setManaged(true);
        if (changed) resizeForValidation(dialog);
    }

    private static void clearError(Dialog<?> dialog, Label error) {
        boolean changed = error.isVisible();
        error.setVisible(false);
        error.setManaged(false);
        if (changed) resizeForValidation(dialog);
    }

    private static void resizeForValidation(Dialog<?> dialog) {
        if (!dialog.isShowing()) return;
        // Establish the wrapped label's width before measuring the window.
        dialog.getDialogPane().applyCss();
        dialog.getDialogPane().layout();
        dialog.getDialogPane().getScene().getWindow().sizeToScene();
    }

    private static Label label(String text, Node field) {
        Label label = new Label(text);
        label.setLabelFor(field);
        return label;
    }

    private static boolean isPositiveInteger(String text) {
        try {
            return Integer.parseInt(text.trim()) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean isNonNegativeInteger(String text) {
        try {
            return Integer.parseInt(text.trim()) >= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
