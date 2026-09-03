package com.shangin.fractal.app;

import java.util.List;

/** Isolates the JavaFX Glass API needed to name the macOS application menu. */
final class MacApplicationMenu {
    private MacApplicationMenu() {}

    static void setName(String name) {
        if (!System.getProperty("os.name", "").startsWith("Mac")) {
            return;
        }
        try {
            // -Xdock:name names the launcher, but Glass can still install a "java" menu.
            // There is no public JavaFX API for that first, system-owned menu.
            Class<?> applicationType = Class.forName("com.sun.glass.ui.Application");
            Object application = applicationType.getMethod("GetApplication").invoke(null);
            Class<?> macApplicationType = Class.forName("com.sun.glass.ui.mac.MacApplication");
            var getAppleMenu = macApplicationType.getMethod("getAppleMenu");
            // MacApplication itself is package-private, even though this method is public.
            getAppleMenu.setAccessible(true);
            Object menu = getAppleMenu.invoke(application);
            if (menu == null) {
                return; // The focus callback retries after the system menu is installed.
            }
            Class<?> menuType = Class.forName("com.sun.glass.ui.Menu");
            menuType.getMethod("setTitle", String.class).invoke(menu, name);
            Class<?> itemType = Class.forName("com.sun.glass.ui.MenuItem");
            for (Object item : (List<?>) menuType.getMethod("getItems").invoke(menu)) {
                if (!itemType.isInstance(item)) {
                    continue; // Separators are null; leave any submenus untouched.
                }
                String title = (String) itemType.getMethod("getTitle").invoke(item);
                if (title.startsWith("Hide ") && !title.equals("Hide Others")) {
                    itemType.getMethod("setTitle", String.class).invoke(item, "Hide " + name);
                } else if (title.startsWith("Quit ")) {
                    itemType.getMethod("setTitle", String.class).invoke(item, "Quit " + name);
                }
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot name the macOS application menu. "
                    + "Use the JavaFX module exports configured in pom.xml or the macOS run configuration.", exception);
        }
    }
}
