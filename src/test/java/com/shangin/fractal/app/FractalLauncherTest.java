package com.shangin.fractal.app;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class FractalLauncherTest {
    @Test
    void macSelectsMetalWithInitializationFallbacks() {
        var properties = propertiesFor("Mac OS X");
        FractalLauncher.configurePrism(properties);
        assertEquals("mtl,es2,sw", properties.getProperty("prism.order"));
        FractalLauncher.configurePrism(properties);
        assertEquals("mtl,es2,sw", properties.getProperty("prism.order"));
    }

    @Test
    void explicitMacOverridesIncludingEmptyValuesArePreserved() {
        for (String order : new String[]{"es2", "sw", "mtl", "es2,sw", ""}) {
            var properties = propertiesFor("Mac OS X");
            properties.setProperty("prism.order", order);
            FractalLauncher.configurePrism(properties);
            assertEquals(order, properties.getProperty("prism.order"));
        }
    }

    @Test
    void otherPlatformsKeepTheirDefaultsAndOverrides() {
        for (String os : new String[]{"Windows 11", "Linux", "Darwin", ""}) {
            var properties = propertiesFor(os);
            FractalLauncher.configurePrism(properties);
            assertNull(properties.getProperty("prism.order"));
            properties.setProperty("prism.order", "sw");
            FractalLauncher.configurePrism(properties);
            assertEquals("sw", properties.getProperty("prism.order"));
        }
    }

    @Test
    void missingPlatformDoesNotSelectAPipeline() {
        var properties = new Properties();
        FractalLauncher.configurePrism(properties);
        assertNull(properties.getProperty("prism.order"));
    }

    private static Properties propertiesFor(String os) {
        var properties = new Properties();
        properties.setProperty("os.name", os);
        return properties;
    }
}
