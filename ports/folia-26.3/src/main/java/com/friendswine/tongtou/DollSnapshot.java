package com.friendswine.tongtou;

import java.util.UUID;

public record DollSnapshot(UUID id, UUID entityId, WorldPoint point, DollMode mode,
                           long startTick, long rotationStartTick, long revision, long packedPosition) {
    public double seconds(double clock) { return mode.playing() ? Math.max(0, clock-startTick)/20 : 0; }
    public double rotationSeconds(double clock) { return mode.rotating() ? Math.max(0, clock-rotationStartTick)/20 : 0; }
}
