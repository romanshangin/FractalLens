module com.shangin.fractal {
    requires java.desktop;
    requires java.management;
    requires javafx.controls;
    requires org.lwjgl;
    requires org.lwjgl.vulkan;
    requires org.lwjgl.shaderc;

    exports com.shangin.fractal.app to javafx.graphics;
    exports com.shangin.fractal.ui to javafx.graphics;

}
