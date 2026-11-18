package com.shangin.fractal.ui;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.scene.SceneGallery;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import static com.shangin.fractal.ui.SceneGalleryWindow.PREVIEW_HEIGHT;
import static com.shangin.fractal.ui.SceneGalleryWindow.PREVIEW_WIDTH;

/** The JavaFX gallery window: used off macOS and when the AppKit bridge is unavailable. */
final class JavaFxGalleryPresentation implements SceneGalleryWindow.Presentation {
    private final Dialog<Void> dialog = new Dialog<>();
    private final SceneGalleryWindow.Listener listener;
    private final ToDoubleFunction<Window> outputScale;
    private final Map<String, StackPane> previews = new HashMap<>();

    JavaFxGalleryPresentation(Stage owner, SceneGalleryWindow.Listener listener, ToDoubleFunction<Window> outputScale) {
        this.listener = listener;
        this.outputScale = outputScale;
        dialog.setTitle(SceneGalleryWindow.TITLE);
        dialog.setHeaderText(SceneGalleryWindow.TITLE);
        dialog.getDialogPane().setId("scene-gallery");
        dialog.getDialogPane().setPrefWidth(3 * (PREVIEW_WIDTH + 28) + 72);
        DialogAppearance.install(dialog, owner, this::firstCard);
        Button close = DialogAppearance.buttons(dialog, ButtonType.CLOSE);
        // Enter must open the focused place, not close the window.
        close.setDefaultButton(false);
        dialog.initModality(Modality.NONE);
        dialog.setOnHidden(event -> listener.hidden());
        // Show first: preview rasters follow the gallery window's own display scale.
        dialog.show();
        ProgressIndicator loading = new ProgressIndicator();
        loading.setId("scene-gallery-loading");
        loading.setAccessibleText("Loading gallery");
        setContent(loading);
    }

    Dialog<Void> dialog() { return dialog; }

    private Node firstCard() {
        Node card = dialog.getDialogPane().lookup(".gallery-card");
        return card != null ? card : dialog.getDialogPane().lookupButton(ButtonType.CLOSE);
    }

    @Override
    public void showError(String message) {
        Label error = new Label(message);
        error.setId("scene-gallery-error");
        error.setWrapText(true);
        error.getStyleClass().add("dialog-error");
        setContent(error);
    }

    @Override
    public void showCatalog(SceneGallery.Catalog catalog) {
        VBox places = new VBox(10);
        if (!catalog.problems().isEmpty()) {
            Label partial = new Label(SceneGalleryWindow.PARTIAL_HINT);
            partial.setWrapText(true);
            partial.getStyleClass().add("dialog-error");
            places.getChildren().add(partial);
        }
        previews.clear();
        for (Map.Entry<FractalPreset, List<SceneGallery.Entry>> group : catalog.groups().entrySet()) {
            Label heading = new Label(group.getKey().toString());
            heading.getStyleClass().add("dialog-section");
            FlowPane cards = new FlowPane(12, 12);
            cards.setRowValignment(VPos.TOP);
            cards.setAlignment(Pos.TOP_LEFT);
            for (SceneGallery.Entry entry : group.getValue()) cards.getChildren().add(card(entry));
            places.getChildren().addAll(heading, cards);
        }
        setContent(places);
        Node first = firstCard();
        if (first != null && dialog.isShowing()) first.requestFocus();
    }

    private void setContent(Node body) {
        VBox content = new VBox(10);
        content.setPadding(new Insets(0, 20, 0, 20));
        Label hint = new Label(SceneGalleryWindow.HINT);
        hint.setWrapText(true);
        hint.getStyleClass().add("dialog-hint");
        content.getChildren().addAll(hint, body);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(560);
        dialog.getDialogPane().setContent(scroll);
        if (dialog.isShowing()) fitWindow();
    }

    /**
     * Content changes after the window is shown (previews need its display scale, and the
     * catalog may arrive later), so resize it to the new content around its current center,
     * within the visual bounds of its screen.
     */
    private void fitWindow() {
        Window window = dialog.getDialogPane().getScene().getWindow();
        double centerX = window.getX() + window.getWidth() / 2;
        double centerY = window.getY() + window.getHeight() / 2;
        // New content has no skin until CSS is applied, so it would measure as empty.
        dialog.getDialogPane().applyCss();
        window.sizeToScene();
        var screens = Screen.getScreensForRectangle(centerX, centerY, 1, 1);
        Rectangle2D bounds = (screens.isEmpty() ? Screen.getPrimary() : screens.getFirst()).getVisualBounds();
        double width = Math.min(window.getWidth(), bounds.getWidth());
        double height = Math.min(window.getHeight(), bounds.getHeight());
        window.setWidth(width);
        window.setHeight(height);
        window.setX(Math.clamp(centerX - width / 2, bounds.getMinX(), bounds.getMaxX() - width));
        window.setY(Math.clamp(centerY - height / 2, bounds.getMinY(), bounds.getMaxY() - height));
    }

    private Button card(SceneGallery.Entry entry) {
        StackPane preview = new StackPane();
        preview.getStyleClass().add("gallery-preview");
        preview.setMinSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        preview.setPrefSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        preview.setMaxSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        ProgressIndicator loading = new ProgressIndicator();
        loading.setMaxSize(28, 28);
        preview.getChildren().setAll(loading);
        previews.put(entry.id(), preview);
        Text name = cardText(entry.name(), "gallery-card-title");
        Text description = cardText(entry.description(), "gallery-card-description");
        VBox body = new VBox(6, preview, name, description);
        body.setMaxWidth(PREVIEW_WIDTH);
        body.setPrefWidth(PREVIEW_WIDTH);
        body.setAlignment(Pos.TOP_LEFT);
        Button card = new Button();
        card.setId("gallery-" + entry.id());
        card.getStyleClass().add("gallery-card");
        card.setGraphic(body);
        card.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        card.setAlignment(Pos.TOP_LEFT);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setAccessibleText(entry.name() + ". " + entry.description());
        card.setOnAction(event -> listener.open(entry.id()));
        card.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                card.fire();
            }
        });
        return card;
    }

    /**
     * Text with a fixed wrapping width. A wrapping Label inside a button graphic is
     * measured without a width and grows one word per line.
     */
    private static Text cardText(String content, String styleClass) {
        Text text = new Text(content);
        text.getStyleClass().add(styleClass);
        text.setWrappingWidth(PREVIEW_WIDTH);
        return text;
    }

    @Override
    public void showPreview(SceneGallery.Entry entry, int[] pixels, int width, int height) {
        StackPane target = previews.get(entry.id());
        if (target == null) return;
        WritableImage image = new WritableImage(width, height);
        image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        ImageView view = new ImageView(image);
        view.setFitWidth(PREVIEW_WIDTH);
        view.setFitHeight(PREVIEW_HEIGHT);
        view.setSmooth(true);
        target.getChildren().setAll(view);
    }

    @Override
    public void showPreviewFailed(SceneGallery.Entry entry) {
        StackPane target = previews.get(entry.id());
        if (target == null) return;
        Label error = new Label(SceneGalleryWindow.PREVIEW_ERROR);
        error.getStyleClass().addAll("dialog-hint", "gallery-preview-error");
        target.getChildren().setAll(error);
    }

    @Override
    public double outputScale() {
        return outputScale.applyAsDouble(dialog.getDialogPane().getScene().getWindow());
    }

    @Override
    public boolean isShowing() { return dialog.isShowing(); }

    @Override
    public void toFront() {
        ((Stage) dialog.getDialogPane().getScene().getWindow()).toFront();
    }

    @Override
    public void close() { dialog.close(); }
}
