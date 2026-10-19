package com.friendswine.tongtou;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

/** 跨区域战斗读取不可变的最近位置，不在受击者线程读远端攻击者坐标。 */
public final class EntityPositions {
    private record Position(WorldPoint point,long stamp) {}
    private final Map<UUID,Position> positions=new ConcurrentHashMap<>();
    public WorldPoint capture(Entity entity){
        if(!Bukkit.isOwnedByCurrentRegion(entity))return latest(entity.getUniqueId());
        WorldPoint point=WorldPoint.of(entity.getLocation());positions.put(entity.getUniqueId(),new Position(point,System.nanoTime()));return point;
    }
    public WorldPoint latest(UUID id){var value=positions.get(id);return value!=null && System.nanoTime()-value.stamp<2_000_000_000L?value.point:null;}
    public WorldPoint point(Entity entity){return Bukkit.isOwnedByCurrentRegion(entity)?capture(entity):latest(entity.getUniqueId());}
    public void prune(){long now=System.nanoTime();positions.entrySet().removeIf(e->now-e.getValue().stamp>10_000_000_000L);}
    public void clear(){positions.clear();}
}
