package com.shangin.fractal.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MacFormValidationTest {
    @Test void exportRejectsOverflowFractionalAndOversizedTargets() {
        assertEquals(2048, MacFormSheet.parseTarget(" 2048 ", "1024").width());
        for (String invalid : List.of("", "NaN", "2.5", "1", "2147483648", "1000000000"))
            assertThrows(IllegalArgumentException.class, () -> MacFormSheet.parseTarget(invalid, "1024"));
    }
}
