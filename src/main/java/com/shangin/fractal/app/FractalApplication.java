package com.shangin.fractal.app;

import com.shangin.fractal.ui.MainView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class FractalApplication extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        MainView mainView = new MainView();

        Scene scene = new Scene(mainView, 1000, 750);

        stage.setTitle("Fractal Visualizer");
        stage.setScene(scene);
        stage.setMinWidth(700);
        stage.setMinHeight(500);
        stage.setOnHidden(event -> mainView.close());
        stage.show();
    }
}