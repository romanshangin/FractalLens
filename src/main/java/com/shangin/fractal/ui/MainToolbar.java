package com.shangin.fractal.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/** Compact toolbar containing only global application actions. */
public final class MainToolbar extends StackPane {

    private static final double SPACING = 8.0;
    private final Button exportButton;
    private final Label deepZoomLabel;

    public MainToolbar(Runnable onResetView, Runnable onExport) {
        Label title = new Label("FractalUI");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Button resetButton = new Button("Reset View");
        resetButton.setOnAction(event -> onResetView.run());

        exportButton = new Button("Export PNG…");
        exportButton.setOnAction(event -> onExport.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox actions = new HBox(SPACING, title, resetButton, spacer, exportButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(8, 10, 8, 12));

        deepZoomLabel = new Label("DEEP ZOOM");
        deepZoomLabel.setMouseTransparent(true);
        deepZoomLabel.setVisible(false);
        deepZoomLabel.setStyle(
                "-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #b55454;");
        StackPane.setAlignment(deepZoomLabel, Pos.CENTER);

        getChildren().addAll(actions, deepZoomLabel);
        setStyle("-fx-border-color: transparent transparent -fx-box-border transparent;");
    }

    public void setDeepZoom(boolean active) {
        deepZoomLabel.setVisible(active);
    }

    public void setExportInProgress(boolean inProgress) {
        exportButton.setDisable(inProgress);
        exportButton.setText(inProgress ? "Exporting…" : "Export PNG…");
    }
}
