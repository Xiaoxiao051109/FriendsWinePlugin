package com.friendswine.tongtou;

import java.nio.file.*;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.server.ServerLoadEvent;

/** 资源先在onLoad安装，随后复用BetterModel正常启动加载；仅未收到启动结果且缺失模型时补一次重载。 */
public final class ResourceBootstrap implements Listener, AutoCloseable {
    private final FriendsWinePlugin plugin;
    private final ResourceReloadState reloadState = new ResourceReloadState();
    private AutoCloseable subscription = () -> {};
    private CraftEngineResources craftEngine;
    private volatile String status = "等待BetterModel生成客户端资源包";
    public ResourceBootstrap(FriendsWinePlugin plugin) { this.plugin = plugin; }
    public String status() { return status; }
    public void enable() {
        if (Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
            craftEngine = new CraftEngineResources(plugin);
            Bukkit.getPluginManager().registerEvents(craftEngine, plugin);
        }
        subscription = plugin.backend().subscribe(plugin::isEnabled, plugin::resetVisuals, this::completed);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void serverLoaded(ServerLoadEvent event) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            if (!plugin.isEnabled()) return;
            boolean missing = plugin.requiredModels().stream().anyMatch(name -> !plugin.backend().hasModel(name));
            if (!missing) plugin.modelsReady(true);
            // 极快的前置可能在订阅前已完成：仍需一次结果来取得资源包路径并执行CE合包。
            if (reloadState.shouldAutoRebuild(true)) rebuild();
        });
    }
    public void rebuild() {
        if (!plugin.isEnabled() || !reloadState.beginRequest()) return;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                if (!plugin.isEnabled()) return;
                var result = plugin.backend().reload();
                if (result.failure() != null) failed(result.failure());
                else if (result.busy()) {
                    status = "BetterModel正在重载，完成后会自动整合资源";
                }
            } catch (RuntimeException failure) { failed(failure); }
            finally { reloadState.finishRequest(); }
        });
    }
    private void completed(ModelBackend.Reload result) {
        if (!plugin.isEnabled()) return;
        if (!result.busy()) reloadState.observeResult();
        if (result.failure() != null) { failed(result.failure()); return; }
        if (result.busy()) return;
        List<String> missing = plugin.requiredModels().stream()
                .filter(name -> !plugin.backend().hasModel(name)).toList();
        if (!missing.isEmpty()) {
            status = "有" + missing.size() + "个模型未加载，请检查BetterModel导入日志";
            plugin.getLogger().warning(status); return;
        }
        plugin.modelsReady(true);
        var directory = result.pack();
        if (directory == null || !Files.exists(directory)) {
            status = "模型已加载，但BetterModel未导出资源包；请启用其zip或folder输出";
            plugin.getLogger().warning(status); return;
        }
        Path pack = directory.toAbsolutePath().normalize();
        if (craftEngine != null) {
            status = "模型已就绪，正在自动合入CraftEngine资源包";
            craftEngine.accept(pack, result.hash());
        } else {
            status = "模型与资源包已生成：" + pack + "；未安装CE，需由现有资源包托管系统下发";
            plugin.getLogger().info(status);
        }
    }
    private void failed(Throwable failure) {
        plugin.modelsReady(false);
        status = "资源生成失败，可使用 /tongtou resources 重试";
        if (reloadState.reportFailure(failure)) plugin.getLogger().log(Level.WARNING, status, failure);
    }
    void setStatus(String value) { status = value; }
    @Override public void close() {
        reloadState.close();
        try { subscription.close(); } catch (Exception failure) { plugin.getLogger().log(Level.WARNING,"关闭模型重载监听失败",failure); }
        if (craftEngine != null) craftEngine.close();
    }
}
