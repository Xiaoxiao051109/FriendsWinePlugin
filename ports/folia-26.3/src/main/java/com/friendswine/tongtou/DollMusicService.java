package com.friendswine.tongtou;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

/** 每位玩家独立声音槽；停止一个来源只停止该槽当前的具体声音。 */
public final class DollMusicService implements Listener,AutoCloseable {
    private final FriendsWinePlugin plugin;
    private final PartyIndex index;
    private final PartyClock clock;
    private final MusicTimeline timeline;
    private final Map<UUID,ListenerState> listeners=new ConcurrentHashMap<>();
    private volatile boolean closed;
    public DollMusicService(FriendsWinePlugin plugin,PartyIndex index,PartyClock clock)throws IOException{
        this.plugin=plugin;this.index=index;this.clock=clock;
        timeline=MusicTimeline.load(BuiltinAssets.bundled().open("music.json"));
    }
    public void start(){Bukkit.getOnlinePlayers().forEach(this::watch);}
    @EventHandler public void join(PlayerJoinEvent event){watch(event.getPlayer());}
    @EventHandler public void quit(PlayerQuitEvent event){var state=listeners.remove(event.getPlayer().getUniqueId());if(state!=null)state.release();}
    private void watch(Player player){
        plugin.onEntity(player,()->{
            if(closed||listeners.containsKey(player.getUniqueId()))return;
            var state=new ListenerState(player);if(listeners.putIfAbsent(player.getUniqueId(),state)!=null)return;
            state.task=player.getScheduler().runAtFixedRate(plugin,t->{
                try{state.tick();}catch(RuntimeException failure){
                    plugin.getLogger().log(java.util.logging.Level.WARNING,"玩家玩偶音乐控制失败",failure);state.release();
                }
            },state::retired,1,5);
            if(state.task==null)state.retired();
        });
    }
    public void reset(){for(var state:listeners.values())plugin.onEntity(state.player,state::silence,state::retired);}
    @Override public void close(){closed=true;for(var state:List.copyOf(listeners.values()))plugin.onEntity(state.player,state::release,state::retired);}
    private final class ListenerState {
        final Player player;final AtomicBoolean ended=new AtomicBoolean();final Track[] tracks=new Track[timeline.slots()];ScheduledTask task;
        ListenerState(Player player){this.player=player;}
        void tick(){
            if(closed||ended.get()||!player.isOnline()){release();return;}
            if(!plugin.partySettings().music()||!plugin.modelsReady()){silence();return;}
            var point=WorldPoint.of(player.getLocation());
            int limit=plugin.partySettings().soundSources();
            var audible=index.nearby(point,20).stream().sorted(Comparator.comparingDouble((DollSnapshot d)->d.point().distanceSquared(point))
                    .thenComparing(DollSnapshot::id)).limit(limit).toList();
            Set<UUID> chosen=new HashSet<>();audible.forEach(d->chosen.add(d.id()));
            for(int slot=0;slot<tracks.length;slot++)if(tracks[slot]!=null&&!chosen.contains(tracks[slot].id))stop(slot);
            double now=clock.interpolatedTicks();
            for(var doll:audible){
                int slot=-1;for(int i=0;i<tracks.length;i++)if(tracks[i]!=null&&tracks[i].id.equals(doll.id())){slot=i;break;}
                if(slot<0)for(int i=0;i<tracks.length;i++)if(tracks[i]==null){slot=i;break;}
                if(slot<0)continue;
                var cue=timeline.cue(doll.seconds(now));Track track=tracks[slot];
                if(track!=null&&track.start==doll.startTick()&&track.loop==cue.loop())continue;
                stop(slot);
                String sound="tongtou:doll_music_"+slot+"_"+cue.offset();
                // 资源中衰减距离为20格；位置来自值快照，不访问玩偶所在的远端区域。
                player.playSound(new Location(player.getWorld(),doll.point().x(),doll.point().y(),doll.point().z()),sound,SoundCategory.RECORDS,1.5F,1);
                tracks[slot]=new Track(doll.id(),doll.startTick(),cue.loop(),sound);
            }
        }
        void stop(int slot){var track=tracks[slot];if(track!=null){player.stopSound(track.sound,SoundCategory.RECORDS);tracks[slot]=null;}}
        void silence(){for(int i=0;i<tracks.length;i++)stop(i);}
        void release(){if(!ended.compareAndSet(false,true))return;try{silence();}finally{retiredRecords();}}
        void retired(){if(!ended.compareAndSet(false,true))return;retiredRecords();}
        void retiredRecords(){listeners.remove(player.getUniqueId(),this);if(task!=null)task.cancel();}
    }
    private record Track(UUID id,long start,long loop,String sound) {}
}
