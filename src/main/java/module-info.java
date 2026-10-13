module com.shangin.fractal {
    requires java.desktop;
    requires java.management;
    requires javafx.controls;
    requires org.lwjgl;
    requires org.lwjgl.vulkan;
    requires org.lwjgl.shaderc;

    // JavaFX launches the application reflectively; internal model types are not an API.
    opens com.shangin.fractal.app to javafx.graphics;

}
