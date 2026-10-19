package com.friendswine.tongtou;

import com.destroystokyo.paper.event.entity.*;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.*;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.world.*;
import org.bukkit.persistence.PersistentDataType;

/** CE负责唯一家具载体和模型绑定，本类只保存玩法状态并驱动该模型。 */
public final class DollService implements Listener,AutoCloseable {
    private final FriendsWinePlugin plugin;
    private final PartyIndex index;
    private final PartyClock clock;
    private final Map<UUID,RuntimeDoll> loaded=new ConcurrentHashMap<>();
    private final Map<UUID,Long> clicks=new ConcurrentHashMap<>();
    private final NamespacedKey idKey,modeKey,elapsedKey,rotatedKey,savedKey;
    private volatile boolean closed;
    public DollService(FriendsWinePlugin plugin,PartyIndex index,PartyClock clock) {
        this.plugin=plugin;this.index=index;this.clock=clock;
        idKey=DataKeys.key("doll_id");modeKey=DataKeys.key("doll_mode");
        elapsedKey=DataKeys.key("doll_elapsed");rotatedKey=DataKeys.key("doll_rotated");
        savedKey=DataKeys.key("doll_saved_clock");
    }
    @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent event){clicks.remove(event.getPlayer().getUniqueId());}
    public int size(){return loaded.size();}
    public boolean owns(UUID id){return loaded.containsKey(id);}
    public void discover(Entity entity){discover(entity,0);}
    private void discover(Entity entity,int attempt) {
        if(closed || !(entity instanceof ItemDisplay))return;
        plugin.onEntity(entity,()->{
            if(closed || !entity.isValid() || loaded.containsKey(entity.getUniqueId()))return;
            BukkitFurniture furniture=CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if(furniture!=null) {
                if(furniture.id().toString().equals("tongtou:doll"))attach(furniture);
            } else if(attempt<60) entity.getScheduler().runDelayed(plugin,t->discover(entity,attempt+1),null,10);
        });
    }
    private void attach(BukkitFurniture furniture) {
        Entity entity=furniture.baseEntity();
        if(closed || !entity.isValid() || loaded.containsKey(entity.getUniqueId()))return;
        var data=entity.getPersistentDataContainer();
        UUID id;
        try{id=UUID.fromString(data.getOrDefault(idKey,PersistentDataType.STRING,""));}
        catch(IllegalArgumentException absent){id=UUID.randomUUID();}
        long saved=Math.max(0,data.getOrDefault(savedKey,PersistentDataType.LONG,clock.ticks()));
        clock.ensureAtLeast(saved);
        DollPlayback playback=DollPlayback.restore(data.getOrDefault(modeKey,PersistentDataType.INTEGER,0),
                data.getOrDefault(elapsedKey,PersistentDataType.LONG,0L),data.getOrDefault(rotatedKey,PersistentDataType.LONG,0L),saved,clock.ticks());
        RuntimeDoll doll=new RuntimeDoll(furniture,id,playback);
        if(loaded.putIfAbsent(entity.getUniqueId(),doll)!=null)return;
        doll.publish();doll.persist();
        doll.task=entity.getScheduler().runAtFixedRate(plugin,t->{
            try{doll.tick();}catch(RuntimeException failure){
                plugin.getLogger().log(java.util.logging.Level.WARNING,"玩偶控制失败："+doll.id,failure);doll.release();
            }
        },doll::retired,1,1);
        if(doll.task==null)doll.retired();
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void place(FurniturePlaceEvent event) {
        if(!event.furniture().id().toString().equals("tongtou:doll"))return;
        if(!event.player().hasPermission("tongtou.party.use") || loaded.size()>=plugin.partySettings().maxDolls()) {
            event.setCancelled(true);plugin.tell(event.player(),"没有玩偶使用权限，或已加载玩偶数量达到上限。");return;
        }
        Entity entity=event.furniture().baseEntity();
        entity.getScheduler().runDelayed(plugin,t->discover(entity),null,1);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void interact(FurnitureInteractEvent event) {
        if(event.hand()!=InteractionHand.MAIN_HAND || !event.furniture().id().toString().equals("tongtou:doll"))return;
        Player player=event.player();
        if(!player.hasPermission("tongtou.party.use"))return;
        long now=System.nanoTime();Long before=clicks.put(player.getUniqueId(),now);
        event.setCancelled(true);
        if(before!=null && now-before<250_000_000L)return;
        ModeItem.Kind kind=plugin.items().identify(player.getInventory().getItemInMainHand());
        var furniture=event.furniture();
        plugin.onEntity(furniture.baseEntity(),()->{
            attach(furniture);var doll=loaded.get(furniture.baseEntity().getUniqueId());if(doll==null)return;
            if(kind==ModeItem.Kind.REMOTE)plugin.remote().bind(player,doll.snapshot);
            else {doll.playback.toggle(clock.ticks());doll.changed();}
        });
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void broken(FurnitureBreakEvent event) {
        if(event.furniture().id().toString().equals("tongtou:doll"))remove(event.furniture().baseEntity().getUniqueId(),true);
    }
    @EventHandler public void added(EntityAddToWorldEvent event){discover(event.getEntity());}
    @EventHandler public void load(EntitiesLoadEvent event){event.getEntities().forEach(this::discover);}
    @EventHandler public void unload(EntitiesUnloadEvent event){event.getEntities().forEach(e->remove(e.getUniqueId(),true));}
    @EventHandler public void removed(EntityRemoveFromWorldEvent event){remove(event.getEntity().getUniqueId(),true);}
    private void remove(UUID entity,boolean save){var doll=loaded.get(entity);if(doll!=null)plugin.onEntity(doll.entity,()->{
        if(save && doll.entity.isValid())doll.persist();doll.release();
    },doll::retired);}
    public void cycle(DollSnapshot expected,Player actor) {
        var doll=loaded.get(expected.entityId());
        if(doll==null || !doll.id.equals(expected.id())){plugin.tell(actor,"玩偶已移除或所在区块未加载。");return;}
        plugin.onEntity(doll.entity,()->{
            if(closed || doll.ended.get() || !doll.entity.isValid() || index.get(expected.id())==null
                    || !doll.entity.getWorld().getUID().equals(expected.point().world())
                    || !doll.entity.getWorld().isChunkLoaded(doll.entity.getLocation().getBlockX()>>4,doll.entity.getLocation().getBlockZ()>>4)) {
                plugin.tell(actor,"玩偶已移除或所在区块未加载。");return;
            }
            doll.playback.cycle(clock.ticks());doll.changed();
            plugin.tell(actor,switch(doll.playback.mode()) {
                case STOPPED->"已停止";case SQUASH->"已开启：回弹";case FULL->"已加入：自转，音乐继续";case ORBIT->"绕圈模式";
            });
        });
    }
    public void stopAll(){
        for(var doll:List.copyOf(loaded.values())){
            doll.stopRequested.set(true);index.remove(doll.id);
            plugin.onEntity(doll.entity,()->{doll.playback.stop(clock.ticks());doll.stopRequested.set(false);doll.changed();});
        }
    }
    public void resetModelBindings(){for(var doll:loaded.values())plugin.onEntity(doll.entity,doll::stopVisual);}
    @Override public void close(){closed=true;clicks.clear();for(var doll:List.copyOf(loaded.values()))plugin.onEntity(doll.entity,()->{
        if(doll.entity.isValid())doll.persist();doll.release();
    },doll::retired);index.clear();}

    private final class RuntimeDoll {
        final BukkitFurniture furniture;final Entity entity;final UUID id;final DollPlayback playback;
        final AtomicBoolean ended=new AtomicBoolean(),stopRequested=new AtomicBoolean();
        final String animationName;volatile DollSnapshot snapshot;ScheduledTask task;ModelBackend.Handle visual;boolean visualStarted;long revision,age;
        RuntimeDoll(BukkitFurniture furniture,UUID id,DollPlayback playback){
            this.furniture=furniture;entity=furniture.baseEntity();this.id=id;this.playback=playback;animationName="tongtou_doll_"+id;
        }
        void tick(){
            if(closed || ended.get() || !entity.isValid()){release();return;}
            var live=CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if(live!=furniture){persist();release();discover(entity);return;}
            if(!furniture.isValid()){release();return;}
            if(stopRequested.getAndSet(false)){playback.stop(clock.ticks());changed();}
            if(++age%10==0)publish();
            if(age%100==0)persist();
            if(age%5==0)updateVisual();
        }
        void changed(){revision++;publish();persist();updateVisual();}
        void publish(){
            var at=entity.getLocation();
            snapshot=new DollSnapshot(id,entity.getUniqueId(),WorldPoint.of(at),playback.mode(),playback.startTick(),
                    playback.rotationStartTick(),revision,WorldPoint.packed(at.getBlockX(),at.getBlockY(),at.getBlockZ()));
            if(!stopRequested.get() && !ended.get())index.publish(snapshot);
        }
        void persist(){
            var data=entity.getPersistentDataContainer();long now=clock.ticks();
            data.set(idKey,PersistentDataType.STRING,id.toString());data.set(modeKey,PersistentDataType.INTEGER,playback.mode().ordinal());
            data.set(elapsedKey,PersistentDataType.LONG,playback.elapsed(now));data.set(rotatedKey,PersistentDataType.LONG,playback.rotated(now));
            // 自有PDC随真实基实体保存，不修改CE的variant/data等额外存档字段。
            data.set(savedKey,PersistentDataType.LONG,now);
        }
        void updateVisual(){
            if(!plugin.modelsReady()){stopVisual();return;}
            var current=plugin.backend().find(entity,"tt_doll");
            if(current==null){
                current=plugin.backend().attach(entity,"tt_doll",false,handle->{});
                current.prime();
            }
            if(visual==null||current==null||!current.same(visual)){stopVisual();visual=current;}
            if(visual==null || visual.closed())return;
            // 停止时仍保留同一个姿态采样器，让下一帧写回defaultFrame；直接移除动画会冻结最后的压缩/旋转。
            if(!visualStarted) {
                visualStarted=visual.continuous(animationName,()->snapshot,clock::interpolatedTicks,plugin::animationSettings,false);
            }
        }
        void stopVisual(){if(visual!=null && !visual.closed())visual.stop(animationName);visual=null;visualStarted=false;}
        void release(){if(!ended.compareAndSet(false,true))return;index.remove(id);loaded.remove(entity.getUniqueId(),this);if(task!=null)task.cancel();stopVisual();}
        void retired(){if(!ended.compareAndSet(false,true))return;index.remove(id);loaded.remove(entity.getUniqueId(),this);if(task!=null)task.cancel();}
    }
}
