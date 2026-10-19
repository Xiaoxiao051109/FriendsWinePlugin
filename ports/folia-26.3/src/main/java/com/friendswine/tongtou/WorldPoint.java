package com.friendswine.tongtou;

import java.util.UUID;
import org.bukkit.Location;

public record WorldPoint(UUID world, double x, double y, double z) {
    public WorldPoint {
        if (world == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("位置快照无效");
    }
    public static WorldPoint of(Location at) { return new WorldPoint(at.getWorld().getUID(), at.getX(), at.getY(), at.getZ()); }
    public double distanceSquared(WorldPoint other) {
        if (!world.equals(other.world)) return Double.POSITIVE_INFINITY;
        double dx = x-other.x, dy=y-other.y, dz=z-other.z; return dx*dx+dy*dy+dz*dz;
    }
    public static long packed(int x, int y, int z) { return ((long)x & 0x3ffffffL) << 38 | ((long)z & 0x3ffffffL) << 12 | ((long)y & 0xfffL); }
}
