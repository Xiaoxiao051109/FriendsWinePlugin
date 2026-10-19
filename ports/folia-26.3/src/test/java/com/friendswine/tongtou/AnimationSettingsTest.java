package com.friendswine.tongtou;

import com.friendswine.JellyAnimation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnimationSettingsTest {
    @Test void originalKeys() {
        var settings = new AnimationSettings(1, 50, 50, 1);
        assertEquals(1.5, settings.frame(0.25).x(), 1e-6);
        assertEquals(0.5, settings.frame(0.25).y(), 1e-6);
        assertEquals(1, settings.frame(0.45833).y(), 1e-6);
        assertEquals(1, settings.frame(settings.duration()).x(), 1e-6);
        assertEquals(-360, settings.frame(settings.duration()).yaw(), 1e-4);
        assertEquals(19, settings.ticks());
    }
    @Test void cyclesAndDisabledRotation() {
        var settings = new AnimationSettings(3, 90, 200, 0);
        assertEquals(3 * JellyAnimation.LENGTH, settings.duration());
        for (double t = 0; t < settings.duration(); t += 0.007) {
            assertTrue(settings.frame(t).x() >= 1);
            assertTrue(settings.frame(t).y() > 0);
            assertEquals(0, settings.frame(t).yaw(), 1e-6);
        }
    }
    @Test void invalidInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AnimationSettings(0, 50, 50, 1));
        assertThrows(IllegalArgumentException.class, () -> new AnimationSettings(1, 100, 50, 1));
        assertThrows(IllegalArgumentException.class, () -> new AnimationSettings(1, 50, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> new AnimationSettings(1, 50, 50, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new AnimationSettings(1, 50, 50, 1).frame(Double.NaN));
    }
    @Test void sharedModMathRegression() { JellyAnimation.main(new String[0]); }
}
