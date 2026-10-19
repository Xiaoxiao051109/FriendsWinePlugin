package com.friendswine.tongtou;

import kr.toxicity.model.api.bone.BoneMovement;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BetterModelKeyframeTest {
    @Test void deltasSumToOriginalDurationAndScaleIsNotDoubled() {
        var settings = new AnimationSettings(1, 50, 50, 1);
        var frames = ModernAnimationFrames.keyframes(settings, new Vector3f());
        double time = 0;
        for (int i = 0; i < frames.size(); i++) {
            var frame = frames.get(i);
            time += frame.time();
            var actual = frame.animate(new BoneMovement(), new BoneMovement());
            var expected = settings.frame(time);
            assertEquals(expected.x(), actual.scale().x, 0.0001);
            assertEquals(expected.y(), actual.scale().y, 0.0001);
            assertEquals(1, actual.scale().z, 0.0001);
            assertTrue(frame.time() >= 0 && frame.time() <= 0.026);
        }
        assertEquals(settings.duration(), time, 1e-6);
    }
    @Test void finalRootReturnsToOriginalPositionAndSize() {
        var settings = new AnimationSettings(2, 50, 50, 1);
        var frames = ModernAnimationFrames.keyframes(settings, new Vector3f(0.2F, 0.5F, -0.3F));
        var last = frames.getLast().animate(new BoneMovement(), new BoneMovement());
        assertEquals(1, last.scale().x, 1e-5);
        assertEquals(1, last.scale().y, 1e-5);
        assertTrue(last.position().length() < 1e-5);
    }
    @Test void initialFrameKeepsOriginalSizePositionAndRotation() {
        var settings = new AnimationSettings(1, 50, 50, 1);
        var keyframes = ModernAnimationFrames.keyframes(settings, new Vector3f(0.25F, 0.5F, -0.4F));
        var first = keyframes.get(0).animate(new BoneMovement(), new BoneMovement());
        assertEquals(1F, first.scale().x, 1e-6);
        assertEquals(1F, first.scale().y, 1e-6);
        assertEquals(1F, first.scale().z, 1e-6);
        assertTrue(first.position().lengthSquared() < 1e-10);
        assertEquals(0F, first.rawRotation().lengthSquared(), 1e-10);
    }

}
