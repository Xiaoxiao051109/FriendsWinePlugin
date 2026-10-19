package com.friendswine.tongtou;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Mob;

/** 持续动画与模型后端解耦；切源只更新快照，不拆建显示。 */
public final class PartyVisuals implements AutoCloseable {
    private final FriendsWinePlugin plugin;
    private final PartyClock clock;
    private final Map<UUID,Handle> active=new ConcurrentHashMap<>();
    private volatile boolean closed;
    public PartyVisuals(FriendsWinePlugin plugin,PartyClock clock){this.plugin=plugin;this.clock=clock;}
    public boolean contains(UUID id){return active.containsKey(id);}
    public int size(){return active.size();}
    public void update(Mob entity,DollSnapshot snapshot){
        if(closed||!plugin.modelsReady()){remove(entity);return;}
        var current=active.get(entity.getUniqueId());
        if(current!=null){
            if(current.ended.get())return;
            if(current.trackers.stream().anyMatch(ModelBackend.Handle::closed)){current.release();return;}
            current.snapshot=snapshot;return;
        }
        if(active.size()>=plugin.partySettings().maxVisual())return;
        plugin.cancelManual(entity.getUniqueId());var trackers=plugin.backend().existing(entity);
        if(trackers.stream().anyMatch(t->t.temporary()&&t.model().startsWith("tt_")))return;
        Handle handle=new Handle(entity,snapshot);
        if(active.putIfAbsent(entity.getUniqueId(),handle)!=null)return;
        try{
            if(trackers.isEmpty()){
                var match=plugin.catalog().resolve(entity.getType().getKey().toString(),Appearance.capture(entity));
                if(match.isEmpty()||!plugin.backend().hasModel(match.get().model())){handle.release();return;}
                handle.owned=plugin.backend().attach(entity,match.get().model(),true,tracker->{
                    if(!tracker.continuous(handle.animation,()->handle.snapshot,clock::interpolatedTicks,plugin::animationSettings,true))
                        throw new IllegalStateException("范围模型没有动画根骨骼");
                });
                handle.trackers=List.of(handle.owned);handle.owned.prime();
            }else{
                if(!plugin.partySettings().animateExisting()){handle.release();return;}
                handle.trackers=trackers;
                for(var tracker:trackers)tracker.continuous(handle.animation,()->handle.snapshot,clock::interpolatedTicks,plugin::animationSettings,true);
            }
        }catch(RuntimeException failure){handle.release();throw failure;}
    }
    public void remove(Mob entity){var handle=active.get(entity.getUniqueId());if(handle!=null)handle.release();}
    public void retired(UUID id){var handle=active.get(id);if(handle!=null&&handle.ended.compareAndSet(false,true))active.remove(id,handle);}
    public void reset(){for(var handle:List.copyOf(active.values()))plugin.onEntity(handle.entity,handle::release,()->retired(handle.entity.getUniqueId()));}
    @Override public void close(){closed=true;reset();}
    private final class Handle {
        final Mob entity;final String animation="tongtou_area_"+UUID.randomUUID().toString().replace("-","");
        final AtomicBoolean ended=new AtomicBoolean();volatile DollSnapshot snapshot;
        List<ModelBackend.Handle> trackers=List.of();ModelBackend.Handle owned;
        Handle(Mob entity,DollSnapshot snapshot){this.entity=entity;this.snapshot=snapshot;}
        void release(){
            if(!ended.compareAndSet(false,true))return;
            try{
                for(var tracker:trackers)try{if(!tracker.closed())tracker.stop(animation);}
                catch(RuntimeException failure){plugin.getLogger().log(java.util.logging.Level.WARNING,"停止范围动画失败",failure);}
                if(owned!=null)owned.removeOwned();
            }finally{active.remove(entity.getUniqueId(),this);}
        }
    }
}
