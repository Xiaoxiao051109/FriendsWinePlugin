package com.friendswine.tongtou;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 只包含值快照，不从其他区域解引用实体；横向32格分桶，距离仍用三维球体。 */
public final class PartyIndex {
    private record Cell(UUID world, int x, int z) {}
    private final Map<UUID, DollSnapshot> byId = new ConcurrentHashMap<>();
    private final Map<Cell, Map<UUID, DollSnapshot>> cells = new ConcurrentHashMap<>();
    private static Cell cell(WorldPoint p) { return new Cell(p.world(), (int)Math.floor(p.x()/32), (int)Math.floor(p.z()/32)); }
    public synchronized void publish(DollSnapshot value) {
        var previous = byId.put(value.id(), value);
        if (previous != null && !cell(previous.point()).equals(cell(value.point()))) removeCell(previous);
        cells.computeIfAbsent(cell(value.point()), key -> new ConcurrentHashMap<>()).put(value.id(), value);
    }
    public synchronized void remove(UUID id) { var old = byId.remove(id); if (old != null) removeCell(old); }
    private void removeCell(DollSnapshot old) {
        var bucket = cells.get(cell(old.point()));
        if (bucket != null) { bucket.remove(old.id()); if (bucket.isEmpty()) cells.remove(cell(old.point()), bucket); }
    }
    public DollSnapshot get(UUID id) { return byId.get(id); }
    public int size() { return byId.size(); }
    public boolean hasActive() { return byId.values().stream().anyMatch(d -> d.mode().playing()); }
    public List<DollSnapshot> all() { return List.copyOf(byId.values()); }
    public DollSnapshot nearest(WorldPoint point, double radius) {
        return nearby(point, radius).stream().min(Comparator.comparingDouble((DollSnapshot d) -> d.point().distanceSquared(point))
                .thenComparingLong(DollSnapshot::packedPosition).thenComparing(DollSnapshot::id)).orElse(null);
    }
    public List<DollSnapshot> nearby(WorldPoint point, double radius) {
        if (!Double.isFinite(radius) || radius < 0 || radius > 256) throw new IllegalArgumentException("查询半径无效");
        List<DollSnapshot> result = new ArrayList<>();
        int x0=(int)Math.floor((point.x()-radius)/32), x1=(int)Math.floor((point.x()+radius)/32);
        int z0=(int)Math.floor((point.z()-radius)/32), z1=(int)Math.floor((point.z()+radius)/32);
        for (int x=x0;x<=x1;x++) for (int z=z0;z<=z1;z++) {
            var bucket=cells.get(new Cell(point.world(),x,z));
            if (bucket == null) continue;
            for (var doll : bucket.values()) if (byId.get(doll.id())==doll && doll.mode().playing() && doll.point().distanceSquared(point)<=radius*radius) result.add(doll);
        }
        return result;
    }
    public void clear() { cells.clear(); byId.clear(); }
}
