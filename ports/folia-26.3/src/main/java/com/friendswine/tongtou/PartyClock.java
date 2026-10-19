package com.friendswine.tongtou;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import org.bukkit.Bukkit;

/** 全服逻辑tick只在全局调度器前进；重启不把离线现实时间算进播放进度。 */
public final class PartyClock implements AutoCloseable {
    private final FriendsWinePlugin plugin;
    private final AtomicLong ticks = new AtomicLong();
    private final AtomicBoolean saving = new AtomicBoolean();
    private volatile long tickNanos = System.nanoTime();
    private volatile long day;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask task;
    public PartyClock(FriendsWinePlugin plugin) {
        this.plugin=plugin;
        var worlds=Bukkit.getWorlds(); if(!worlds.isEmpty())day=Math.floorDiv(worlds.getFirst().getFullTime(),24000);
        Path file=plugin.getDataFolder().toPath().resolve("party-clock.txt");
        try { if (Files.isRegularFile(file)) ticks.set(Math.max(0,Long.parseLong(Files.readString(file).trim()))); }
        catch (IOException | NumberFormatException failure) { plugin.getLogger().warning("播放时钟记录无效，将按玩偶存档重新校正。"); }
    }
    public void start() {
        task=Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> {
            long tick=ticks.incrementAndGet(); tickNanos=System.nanoTime();
            var worlds=Bukkit.getWorlds(); if (!worlds.isEmpty()) day=Math.floorDiv(worlds.getFirst().getFullTime(),24000);
            if (tick%200==0 && saving.compareAndSet(false,true)) Bukkit.getAsyncScheduler().runNow(plugin, a -> {
                try { save(); } finally { saving.set(false); }
            });
        },1,1);
    }
    public long ticks() { return ticks.get(); }
    public long day() { return day; }
    public void ensureAtLeast(long value) { ticks.accumulateAndGet(Math.max(0,value),Math::max); }
    public double interpolatedTicks() { return ticks.get()+Math.clamp((System.nanoTime()-tickNanos)/50_000_000.0,0,1); }
    private synchronized void save() {
        try {
            Path file=plugin.getDataFolder().toPath().resolve("party-clock.txt"); Path temp=file.resolveSibling("party-clock.txt.tmp");
            Files.writeString(temp,Long.toString(ticks.get())); Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) { plugin.getLogger().warning("无法保存播放时钟："+failure.getMessage()); }
    }
    @Override public void close() { if (task!=null) task.cancel(); save(); }
}
