package com.friendswine.tongtou;

import com.destroystokyo.paper.event.entity.*;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.world.*;
import org.bukkit.util.Vector;

/** 每个生物的移动和模型操作都由其EntityScheduler执行；共享层仅保存值。 */
public final class PartyController implements Listener,AutoCloseable {
    private final FriendsWinePlugin plugin;
    private final PartyIndex index;
    private final PartyClock clock;
    private final EntityPositions positions;
    private final PartySlots slots=new PartySlots();
    private final Map<UUID,Watcher> watched=new ConcurrentHashMap<>();
    private final PartyVisuals visuals;
    private volatile boolean closed;
    private ScheduledTask maintenance;
    public PartyController(FriendsWinePlugin plugin,PartyIndex index,PartyClock clock,EntityPositions positions){
        this.plugin=plugin;this.index=index;this.clock=clock;this.positions=positions;visuals=new PartyVisuals(plugin,clock);
    }
    public void start(){maintenance=Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,t->{
        positions.prune();slots.prune(new HashSet<>(index.all().stream().map(DollSnapshot::id).toList()));
    },100,100);}
    public int watched(){return watched.size();}
    public int dancing(){return visuals.size();}
    public boolean dancing(UUID id){return visuals.contains(id);}
    public void resetVisuals(){visuals.reset();}
    public void discover(Entity entity){
        if(closed || !(entity instanceof Mob mob))return;
        plugin.onEntity(mob,()->{
            if(closed || !mob.isValid() || watched.containsKey(mob.getUniqueId())
                    ||watched.size()>=plugin.partySettings().maxTracked())return;
            var watcher=new Watcher(mob);if(watched.putIfAbsent(mob.getUniqueId(),watcher)==null)watcher.schedule(1);
        });
    }
    @EventHandler public void added(EntityAddToWorldEvent event){discover(event.getEntity());}
    @EventHandler public void loaded(EntitiesLoadEvent event){event.getEntities().forEach(this::discover);}
    @EventHandler public void tracked(PlayerTrackEntityEvent event){discover(event.getEntity());plugin.dolls().discover(event.getEntity());}
    @EventHandler public void unloaded(EntitiesUnloadEvent event){event.getEntities().forEach(e->retire(e.getUniqueId()));}
    @EventHandler public void removed(EntityRemoveFromWorldEvent event){retire(event.getEntity().getUniqueId());}
    private void retire(UUID id){var watcher=watched.get(id);if(watcher!=null)watcher.retired();}
    @Override public void close(){
        closed=true;if(maintenance!=null)maintenance.cancel();
        for(var watcher:List.copyOf(watched.values()))plugin.onEntity(watcher.mob,watcher::release,watcher::retired);
        visuals.close();slots.clear();
    }
    private final class Watcher {
        final Mob mob;final AtomicBoolean ended=new AtomicBoolean();ScheduledTask task;
        UUID source;PartySlots.Target target;WorldPoint ownedPathEnd;long nextSlot,nextPath,lastTick,nextTrade;
        double width,height;boolean orbitMode,orbitPath;
        Watcher(Mob mob){this.mob=mob;}
        void schedule(long delay){
            if(closed||ended.get())return;
            task=mob.getScheduler().runDelayed(plugin,t->{
                if(closed||ended.get()||!mob.isValid()||mob.isDead()){release();return;}
                try{
                    WorldPoint here=positions.capture(mob);long now=clock.ticks();
                    if(mob instanceof Villager villager && now>=nextTrade){plugin.trades().onVillagerTick(villager);nextTrade=now+20;}
                    var doll=index.nearest(here,50);
                    if(index.hasActive())plugin.peace().tick(mob);
                    if(doll==null){releaseControl();schedule(5);return;}
                    if(!doll.id().equals(source)){
                        releasePath();if(source!=null)slots.release(source,mob.getUniqueId());
                        source=doll.id();target=null;orbitMode=orbitPath=false;nextSlot=nextPath=0;
                    }
                    move(doll,now);
                    if(doll.point().distanceSquared(here)<=100) {
                        if(now>=nextVisual){visuals.update(mob,doll);nextVisual=now+5;}
                        else if(visuals.contains(mob.getUniqueId()))visuals.update(mob,doll);
                    }else visuals.remove(mob);
                    lastTick=now;schedule(1);
                }catch(RuntimeException failure){
                    plugin.getLogger().log(java.util.logging.Level.WARNING,"生物范围控制失败："+mob.getUniqueId(),failure);release();
                }
            },this::retired,delay);
            if(task==null)retired();
        }
        long nextVisual;
        void move(DollSnapshot doll,long now){
            var at=mob.getLocation();var current=new PartySlots.Target(at.getX(),at.getY(),at.getZ());
            boolean wantsOrbit=doll.mode()==DollMode.ORBIT;
            if(wantsOrbit && (!orbitMode||target==null)){
                var anchor=OrbitMotion.anchor(doll.point(),current,mob.getWidth(),p->PartyTerrain.safe(mob,p));
                if(anchor!=null){
                    releasePath();orbitPath=false;target=anchor;width=mob.getWidth();height=mob.getHeight();nextSlot=now+20;
                    slots.update(source,mob.getUniqueId(),target,width,height);
                }
            }
            if(!wantsOrbit)orbitPath=false;
            orbitMode=wantsOrbit;
            if(now>=nextSlot){
                if(target==null||width!=mob.getWidth()||height!=mob.getHeight()||!PartyTerrain.safe(mob,target)){
                    width=mob.getWidth();height=mob.getHeight();
                    target=slots.allocateResolved(source,mob.getUniqueId(),doll.point(),current,width,height,p->PartyTerrain.walkable(mob,p));orbitPath=false;nextPath=0;
                }
                nextSlot=now+20;
            }
            if(target==null&&(PartyTerrain.water(mob)||PartyTerrain.flying(mob))){releasePath();return;}
            boolean orbit=doll.mode()==DollMode.ORBIT&&target!=null;
            double speed=plugin.orbitSpeed();
            if(orbitPath&&target!=null&&current.squared(target)<=1&&Math.abs(current.y()-target.y())<=0.1)orbitPath=false;
            if(orbit&&!orbitPath&&current.squared(target)<=1){
                var frame=OrbitMotion.advance(doll.point(),current,target,mob.getVelocity(),speed,now-lastTick,p->PartyTerrain.walkable(mob,p));
                target=frame.target();slots.update(source,mob.getUniqueId(),target,width,height);
                if(!frame.needsPath()){releasePath();mob.setVelocity(frame.velocity());return;}
                orbitPath=true;nextPath=0;
            }
            var goal=target==null?new PartySlots.Target(doll.point().x(),Math.floor(doll.point().y()),doll.point().z()):target;
            Vector toward=new Vector(goal.x()-at.getX(),goal.y()-at.getY(),goal.z()-at.getZ());double distance=toward.length();
            double arrival=target==null?5:orbit?0.01:0.18;
            if(distance<=arrival){releasePath();Vector motion=mob.getVelocity();mob.setVelocity(new Vector(motion.getX()*0.45,motion.getY(),motion.getZ()*0.45));return;}
            Location destination=new Location(mob.getWorld(),goal.x(),goal.y(),goal.z());
            if(now>=nextPath){
                if(!Bukkit.isOwnedByCurrentRegion(destination)||!mob.getWorld().isChunkLoaded(destination.getBlockX()>>4,destination.getBlockZ()>>4)){
                    var direction=toward.clone().normalize().multiply(Math.min(6,distance));
                    destination=at.clone().add(direction);
                }
                if(Bukkit.isOwnedByCurrentRegion(destination)&&mob.getWorld().isChunkLoaded(destination.getBlockX()>>4,destination.getBlockZ()>>4)){
                    if(mob.getPathfinder().moveTo(destination,1.1)){
                        var path=mob.getPathfinder().getCurrentPath();
                        ownedPathEnd=path!=null&&path.getFinalPoint()!=null?WorldPoint.of(path.getFinalPoint()):null;
                    }
                }
                nextPath=now+10;
            }
            if(orbitPath){
                if(!PartyTerrain.water(mob)&&!PartyTerrain.flying(mob)&&OrbitMotion.shouldJump(current,goal,mob.isOnGround()))
                    mob.setJumping(true);
                return;
            }
            if(WorldPoint.of(at).distanceSquared(doll.point())<=100&&distance>0){
                Vector direction=toward.multiply(1/distance),motion=mob.getVelocity();
                double acceleration=Math.min(0.035,Math.min((distance-arrival)*0.06,0.22-motion.dot(direction)));
                if(acceleration>0)mob.setVelocity(motion.add(direction.multiply(acceleration)));
            }
        }
        void releasePath(){
            if(ownedPathEnd!=null){
                var path=mob.getPathfinder().getCurrentPath();
                if(path!=null&&path.getFinalPoint()!=null&&ownedPathEnd.distanceSquared(WorldPoint.of(path.getFinalPoint()))<1e-8)
                    mob.getPathfinder().stopPathfinding();
            }
            ownedPathEnd=null;
        }
        void releaseControl(){
            releasePath();if(source!=null)slots.release(source,mob.getUniqueId());source=null;target=null;orbitMode=orbitPath=false;visuals.remove(mob);
        }
        void release(){if(!ended.compareAndSet(false,true))return;try{releaseControl();}finally{watched.remove(mob.getUniqueId(),this);if(task!=null)task.cancel();}}
        void retired(){if(!ended.compareAndSet(false,true))return;if(source!=null)slots.release(source,mob.getUniqueId());
            visuals.retired(mob.getUniqueId());watched.remove(mob.getUniqueId(),this);if(task!=null)task.cancel();}
    }
}
