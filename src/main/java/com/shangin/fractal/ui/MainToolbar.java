package com.shangin.fractal.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Compact toolbar containing only global application actions. */
public final class MainToolbar extends HBox {

    private static final double SPACING = 8.0;
    private final Button exportButton;

    public MainToolbar(Runnable onResetView, Runnable onExport) {
        super(SPACING);

        Label title = new Label("FractalUI");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Button resetButton = new Button("Reset View");
        resetButton.setOnAction(event -> onResetView.run());

        exportButton = new Button("Export PNG…");
        exportButton.setOnAction(event -> onExport.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(title, resetButton, spacer, exportButton);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(8, 10, 8, 12));
        setStyle("-fx-border-color: transparent transparent -fx-box-border transparent;");
    }

    public void setExportInProgress(boolean inProgress) {
        exportButton.setDisable(inProgress);
        exportButton.setText(inProgress ? "Exporting…" : "Export PNG…");
    }
}
