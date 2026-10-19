package com.friendswine.tongtou;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.logging.Level;
import net.momirealms.craftengine.bukkit.api.event.AsyncResourcePackCacheEvent;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import org.bukkit.Bukkit;
import org.bukkit.event.*;

/** 使用CE官方外部资源缓存和现有工作流，不修改托管地址、端口或其他插件配置。 */
public final class CraftEngineResources implements Listener, AutoCloseable {
    private final FriendsWinePlugin plugin;
    private final AtomicReference<Request> pending = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile String built;
    private volatile boolean closed;
    private volatile Path pack;
    private record Request(Path path, String hash) {}
    CraftEngineResources(FriendsWinePlugin plugin) { this.plugin = plugin; }
    public void accept(Path path, String hash) {
        try {
            Path content = Bukkit.getPluginManager().getPlugin("CraftEngine").getDataFolder().toPath().resolve("resources/tongtou");
            hash = ResourceFingerprint.combine(hash,content);
        } catch (java.io.IOException failure) { stopWith("无法校验CE资源摘要",failure); return; }
        pack = path;
        if (hash.equals(built)) { plugin.resourceStatus("CraftEngine资源已是最新"); return; }
        pending.set(new Request(path, hash));
        if (scheduled.compareAndSet(false, true)) schedule(0);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void cache(AsyncResourcePackCacheEvent event) {
        Path current = pack;
        if (!closed && current != null && Files.exists(current))
            ResourcePackPaths.register(event.cacheData().externalZips(), event.cacheData().externalFolders(), current);
    }
    private void schedule(int attempt) {
        if (closed || !plugin.isEnabled()) { scheduled.set(false); return; }
        Bukkit.getAsyncScheduler().runDelayed(plugin, task -> attempt(attempt), attempt == 0 ? 1 : 2000, TimeUnit.MILLISECONDS);
    }
    private void attempt(int attempt) {
        if (closed || !plugin.isEnabled()) { scheduled.set(false); return; }
        var ce = BukkitCraftEngine.instance();
        if (!ce.isFullyLoaded() || ce.isReloading() || ce.resourceOperations().isBusy()) {
            if (attempt < 60) schedule(attempt + 1);
            else stopWith("CraftEngine长时间忙碌，资源已安装，可用 /tongtou resources 重试", null);
            return;
        }
        Request current = pending.get();
        if (current == null) { scheduled.set(false); return; }
        try {
            // 默认CE工作流会生成、校验、上传并下发，沿用管理员现有设置。
            ce.packManager().initCachedAssets();
            String workflow = plugin.getConfig().getString("resources.ce-workflow", "default");
            if (!ce.packManager().workflowNames().contains(workflow))
                throw new IllegalStateException("CE工作流不存在：" + workflow);
            ce.packManager().runWorkflow(workflow);
            built = current.hash();
            pending.compareAndSet(current, null);
            plugin.resourceStatus("内置模型已自动合入CraftEngine，并执行其资源包工作流");
            plugin.getLogger().info("内置模型已自动合入CraftEngine；客户端仍需接受服务器资源包。");
            scheduled.set(false);
            if (pending.get() != null && scheduled.compareAndSet(false, true)) schedule(0);
        } catch (Exception failure) {
            if (ce.resourceOperations().isBusy() && attempt < 60) schedule(attempt + 1);
            else stopWith("CraftEngine自动合包失败，可用 /tongtou resources 重试", failure);
        }
    }
    private void stopWith(String message, Exception failure) {
        scheduled.set(false);
        plugin.resourceStatus(message);
        if (failure == null) plugin.getLogger().warning(message);
        else plugin.getLogger().log(Level.WARNING, message, failure);
    }
    @Override public void close() { closed = true; pending.set(null); }
}
