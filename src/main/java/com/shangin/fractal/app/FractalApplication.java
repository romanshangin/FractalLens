package com.shangin.fractal.app;

import com.shangin.fractal.ui.MainView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Screen;

public class FractalApplication extends Application {

    private MainView mainView;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        ApplicationIcon.install(stage);
        mainView = new MainView(stage);

        var screen = Screen.getPrimary().getBounds();
        Scene scene = new Scene(mainView, Math.floor(screen.getWidth() * 0.75),
                Math.floor(screen.getHeight() * 0.75));

        stage.setScene(scene);
        stage.setMinWidth(480);
        stage.setMinHeight(320);
        stage.setFullScreenExitHint("");
        stage.setOnHidden(event -> mainView.close());
        stage.focusedProperty().addListener((observable, wasFocused, focused) -> {
            if (focused) {
                Platform.runLater(() -> MacApplicationMenu.setName("FractalUI"));
            }
        });
        stage.show();
        stage.centerOnScreen();
        Platform.runLater(() -> MacApplicationMenu.setName("FractalUI"));
        mainView.getCenter().requestFocus();
    }

    @Override
    public void stop() {
        if (mainView != null) {
            mainView.close();
        }
    }
}
