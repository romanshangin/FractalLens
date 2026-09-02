module com.shangin.fractal {
    requires java.desktop;
    requires javafx.controls;
    requires org.lwjgl;
    requires org.lwjgl.vulkan;

    exports com.shangin.fractal.app to javafx.graphics;

}
