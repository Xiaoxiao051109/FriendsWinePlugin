package com.friendswine.tongtou;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class FriendsWinePlugin extends JavaPlugin implements Listener, CommandExecutor {
    private final Map<UUID, Long> clicks = new ConcurrentHashMap<>();
    private SessionBook<String> sessions;
    private volatile ModelCatalog catalog = new ModelCatalog(List.of());
    private volatile AnimationSettings animation;
    private volatile PartySettings partySettings;
    private ModeItem item;
    private BetterModelAnimations entities;
    private Plugin cleanupHost;
    private ResourceBootstrap resources;
    private BuiltinAssets.InstallResult installed;
    private PartyClock clock;
    private PartyIndex partyIndex;
    private EntityPositions positions;
    private Native26Bridge nativeBridge;
    private DollService dolls;
    private RemoteService remote;
    private PartyController party;
    private PeaceService peace;
    private NitwitTradeService trades;
    private DollMusicService music;
    private volatile boolean modelsReady;
    private ServerProfile profile;
    private ModelBackend backend;
    private Set<String> entityTypes=Set.of();

    @Override public void onLoad() {
        Plugin bm = getServer().getPluginManager().getPlugin("BetterModel");
        Plugin ce = getServer().getPluginManager().getPlugin("CraftEngine");
        if (bm == null || ce == null) throw new IllegalStateException("缺少BetterModel或CraftEngine前置");
        if(getServer().getPluginManager().getPlugin("TongtouMode")!=null)
            throw new IllegalStateException("请先移除旧TongtouMode插件JAR，不能与FriendsWinePlugin同时安装");
        profile=ServerProfile.select(Bukkit.getMinecraftVersion(),bm.getDescription().getVersion(),Runtime.version().feature());
        nativeBridge=new Native26Bridge();
        backend=ModelBackends.create(this,profile);
        Set<String> registered=new HashSet<>();
        for(EntityType type:EntityType.values())if(type!=EntityType.UNKNOWN&&type!=EntityType.PLAYER)registered.add(type.getKey().toString());
        entityTypes=Set.copyOf(registered);
        try {
            int migrated=LegacyDataMigration.migrate(getDataFolder().toPath().resolveSibling("TongtouMode"),getDataFolder().toPath());
            if(migrated>0)getLogger().info("已从TongtouMode复制"+migrated+"个旧数据文件，保留原目录与新目录中的既有文件。");
            installed = BuiltinAssets.install(SelectedAssets.select(BuiltinAssets.bundled(),profile,entityTypes), getDataFolder().toPath(),
                    bm.getDataFolder().toPath(), ce == null ? null : ce.getDataFolder().toPath());
        } catch (IOException failure) { throw new IllegalStateException("内置资源安装失败", failure); }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        cleanupHost = getServer().getPluginManager().getPlugin("BetterModel");
        if (installed == null) throw new IllegalStateException("内置资源尚未安装，拒绝启动空资源插件");
        getLogger().info("内置资源：" + installed.models() + " 个模型，新建 " + installed.created() + "，更新 " + installed.updated());
        if (!installed.preserved().isEmpty()) getLogger().warning("保留管理员修改的资源：" + installed.preserved());
        if(profile.legacyModels()&&installed.preserved().stream().anyMatch(p->p.equals("ce/configuration/items.yml")||p.startsWith("models/")))
            throw new IllegalStateException("旧版资源档案与管理员修改冲突；文件已保留，请按日志路径合并后重启");
        try { loadSettings(); }
        catch (IOException | IllegalArgumentException failure) { throw new IllegalStateException("通透模式配置无效", failure); }
        sessions = new SessionBook<>(Math.clamp(getConfig().getInt("max-concurrent", 128), 1, 4096));
        item = new ModeItem(this, getConfig().getString("item-id", "tongtou:mode"));
        entities = new BetterModelAnimations(this);
        clock = new PartyClock(this);
        partyIndex = new PartyIndex();
        positions = new EntityPositions();
        dolls = new DollService(this, partyIndex, clock);
        remote = new RemoteService(this, partyIndex);
        peace = new PeaceService(this, partyIndex, positions);
        trades = new NitwitTradeService(this);
        party = new PartyController(this, partyIndex, clock, positions);
        try { music = new DollMusicService(this, partyIndex, clock); }
        catch (IOException failure) { throw new IllegalStateException("内置音乐清单无效", failure); }
        for (Listener listener : List.of(dolls, remote, peace, trades, party, music))
            getServer().getPluginManager().registerEvents(listener, this);
        clock.start(); party.start(); music.start();
        resources = new ResourceBootstrap(this);
        resources.enable();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("tongtou")).setExecutor(this);
        getLogger().info("兼容档案：Minecraft "+profile.minecraft()+" / "+profile.resourceKey()+"；统一26.3模型源，仅加载当前实体类型。");
        if(profile.legacyModels())getLogger().warning("旧模型后端已完成API编译和资源静态校验，但本轮未执行其运行验收；请先在测试服验证。");
        getLogger().info("通透模式已启用；原版外观目录 " + catalog.size() + " 项，玩家始终排除。");
        if (catalog.size() == 0) getLogger().warning("内置catalog.json为空，原版生物模型不可用。");
    }
    @Override public void onDisable() {
        if (resources != null) resources.close();
        if (dolls != null) dolls.close();
        if (party != null) party.close();
        if (music != null) music.close();
        if (clock != null) clock.close();
        if (positions != null) positions.clear();
        if (sessions != null) sessions.shutdown();
        clicks.clear();
    }
    private void loadSettings() throws IOException {
        AnimationSettings next = new AnimationSettings(getConfig().getInt("cycles", 1),
                getConfig().getDouble("compression-percent", 50), getConfig().getDouble("width-percent", 50),
                getConfig().getDouble("rotation-speed", 1));
        PartySettings nextParty = PartySettings.load(getConfig());
        double reach = getConfig().getDouble("reach", 6);
        if (!Double.isFinite(reach) || reach < 1 || reach > 16)
            throw new IllegalArgumentException("交互距离超出范围");
        ModelCatalog nextCatalog = ModelCatalog.load(getDataFolder().toPath().resolve("catalog.json")).forEntities(entityTypes);
        animation = next; partySettings = nextParty; catalog = nextCatalog;
    }
    public ModelCatalog catalog() { return catalog; }
    public ModelBackend backend() { return backend; }
    public ServerProfile profile() { return profile; }
    public ModeItem items() { return item; }
    public DollService dolls() { return dolls; }
    public RemoteService remote() { return remote; }
    public PeaceService peace() { return peace; }
    public Native26Bridge nativeBridge() { return nativeBridge; }
    public NitwitTradeService trades() { return trades; }
    public long day() { return clock.day(); }
    public boolean modelsReady() { return modelsReady; }
    public AnimationSettings animationSettings() { return animation; }
    public double orbitSpeed() { return partySettings.orbitSpeed(); }
    public PartySettings partySettings() { return partySettings; }
    public void cancelManual(UUID id) { sessions.cancel("entity:"+id); }
    public Set<String> requiredModels() {
        Set<String> names = new HashSet<>(catalog.modelNames()); names.add("tt_doll"); return Set.copyOf(names);
    }
    public void resetVisuals() {
        modelsReady = false;
        sessions.cancelAll();
        if (party != null) party.resetVisuals();
        if (dolls != null) dolls.resetModelBindings();
        if (music != null) music.reset();
    }
    public void modelsReady(boolean value) { modelsReady = value; }
    void resourceStatus(String status) { resources.setStatus(status); }

    /** 停用时由仍启用的依赖调度器完成显示回收；实体任务始终跟随所属区域。 */
    public void onEntity(Entity entity, Runnable action) { onEntity(entity, action, null); }
    public void onEntity(Entity entity, Runnable action, Runnable retired) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) { action.run(); return; }
        Plugin host = isEnabled() ? this : cleanupHost;
        if (host == null || !host.isEnabled()) { if (retired != null) retired.run(); return; }
        var task = entity.getScheduler().run(host, tick -> action.run(), retired);
        if (task == null && retired != null) retired.run();
    }
    /** 全局只枚举已加载区块坐标，实体采集移交各区块所属区域，且不加载新区块。 */
    @EventHandler public void loaded(ServerLoadEvent event) { discoverLoaded(); }
    @EventHandler public void ceReload(net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent event) {
        Bukkit.getGlobalRegionScheduler().run(this,task->discoverLoaded());
    }
    private void discoverLoaded() {
        if(!isEnabled()||party==null||dolls==null)return;
        record Loaded(World world,int x,int z) {}
        ArrayDeque<Loaded> queue = new ArrayDeque<>();
        for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks())
            queue.add(new Loaded(world,chunk.getX(),chunk.getZ()));
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            for(int budget=0;budget<32&&!queue.isEmpty();budget++){
                Loaded next=queue.removeFirst();
                Bukkit.getRegionScheduler().run(this,next.world(),next.x(),next.z(),region -> {
                    if(!isEnabled()||!next.world().isChunkLoaded(next.x(),next.z()))return;
                    Chunk chunk=next.world().getChunkAt(next.x(),next.z());
                    if(!chunk.isEntitiesLoaded())return;
                    for(Entity entity:chunk.getEntities()){party.discover(entity);dolls.discover(entity);}
                });
            }
            if(queue.isEmpty())task.cancel();
        },1,1);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        clicks.remove(event.getPlayer().getUniqueId());
    }
    @EventHandler public void removed(EntityRemoveFromWorldEvent event) {
        sessions.cancel("entity:" + event.getEntity().getUniqueId());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interactEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (event.getHand() != EquipmentSlot.HAND || event.getRightClicked() instanceof Player || !ready(player)) return;
        event.setCancelled(true);
        if (!takeClick(player)) return;
        Entity target = event.getRightClicked();
        Location origin = player.getLocation().clone();
        long stamp = System.nanoTime();
        onEntity(target, () -> {
            double reach = getConfig().getDouble("reach", 6);
            if (System.nanoTime() - stamp > 2_000_000_000L || !target.isValid() || target.isDead()
                    || !target.getWorld().getUID().equals(origin.getWorld().getUID())
                    || target.getLocation().distanceSquared(origin) > reach * reach) return;
            if (party.dancing(target.getUniqueId())) { tell(player, "该实体正参与范围蹦迪，请先停止玩偶或将实体移出范围。"); return; }
            var lease = sessions.acquire("entity:" + target.getUniqueId());
            if (lease == null) { tell(player, "该目标正在播放动画，或并发数量已达上限。"); return; }
            try {
                String failure = entities.play(target, lease, animation);
                if (failure != null) { lease.close(); tell(player, failure); }
            } catch (RuntimeException failure) {
                lease.close(); getLogger().log(Level.WARNING, "实体动画失败", failure);
                tell(player, "动画未能启动，已执行清理，请查看服务端日志。");
            }
        });
    }
    private boolean ready(Player player) {
        return player.isSneaking() && player.hasPermission("tongtou.use") && item.matches(player.getInventory().getItemInMainHand());
    }
    private boolean takeClick(Player player) {
        long now = System.nanoTime();
        Long previous = clicks.put(player.getUniqueId(), now);
        return previous == null || now - previous >= Math.clamp(getConfig().getLong("cooldown-millis", 250), 0, 10_000) * 1_000_000L;
    }
    public void tell(Player player, String text) { onEntity(player, () -> player.sendMessage("§b[通透模式] §f" + text)); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("tongtou.admin")) return true;
        if (args.length == 0) return false;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
                if (target == null) { sender.sendMessage("请指定在线玩家：/tongtou give <玩家>"); return true; }
                ModeItem.Kind kind = args.length > 2 ? ModeItem.Kind.parse(args[2]) : ModeItem.Kind.MODE;
                if (kind == null) { sender.sendMessage("物品类型：mode、doll、remote"); return true; }
                onEntity(target, () -> {
                    var left = target.getInventory().addItem(item.create(kind));
                    left.values().forEach(stack -> target.getWorld().dropItemNaturally(target.getLocation(), stack));
                    target.sendMessage("已获得：" + kind.title);
                });
            }
            case "status" -> {
                sender.sendMessage("兼容档案：Minecraft "+profile.minecraft()+" / "+profile.resourceKey()+"；模型源：26.3。");
                sender.sendMessage("原版外观目录：" + catalog.size() + "；活动会话：" + sessions.size() + "；玩家：排除。");
                sender.sendMessage("已加载玩偶：" + dolls.size() + "；范围显示：" + party.dancing() + "；跟踪生物：" + party.watched());
                sender.sendMessage("资源状态：" + resources.status());
            }
            case "resources" -> { resources.rebuild(); sender.sendMessage("已请求重新生成模型资源并整合到CE。"); }
            case "stop" -> { dolls.stopAll(); party.resetVisuals(); music.reset(); sessions.cancelAll(); sender.sendMessage("已请求停止全部玩偶与通透动画，玩偶不会被下一轮扫描自动开启。"); }
            case "reload" -> {
                try { reloadConfig(); loadSettings(); sender.sendMessage("配置与模型目录已重载；并发上限和物品ID的修改需重启生效。"); }
                catch (IOException | IllegalArgumentException failure) { sender.sendMessage("重载失败：" + failure.getMessage()); }
            }
            default -> { return false; }
        }
        return true;
    }
}
