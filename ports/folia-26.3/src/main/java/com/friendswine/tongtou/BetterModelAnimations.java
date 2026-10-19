package com.friendswine.tongtou;

import java.util.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/** 手动会话只依赖内部模型句柄；具体BetterModel API由后端处理。 */
public final class BetterModelAnimations {
    private final FriendsWinePlugin plugin;
    public BetterModelAnimations(FriendsWinePlugin plugin){this.plugin=plugin;}
    public String play(Entity entity,SessionBook<String>.Lease lease,AnimationSettings settings){
        if(entity instanceof Player)return "玩家不参与通透动画。";
        if(!lease.active())return "动画请求已取消。";
        var backend=plugin.backend();var targets=backend.existing(entity);
        if(targets.stream().anyMatch(t->!t.closed()&&t.temporary()&&t.model().startsWith("tt_")))return "上一次临时模型正在回收，请稍后重试。";
        String name="tongtou_"+UUID.randomUUID().toString().replace("-","");ModelBackend.Handle created=null;
        if(targets.isEmpty()){
            String type=entity.getType().getKey().toString();var appearance=Appearance.capture(entity);
            var match=plugin.catalog().resolve(type,appearance);
            if(match.isEmpty())return plugin.catalog().hasEntity(type)?"该实体已有模型，但当前外观条件不匹配："+type+" "+new TreeMap<>(appearance):"尚未提供该实体类型的原版外观模型："+type;
            if(!backend.hasModel(match.get().model()))return "BetterModel尚未加载模型 "+match.get().model()+"，请重载资源。";
            created=backend.attach(entity,match.get().model(),true,handle->{if(!handle.once(name,settings))throw new IllegalStateException("模型没有可动画的根骨骼");});
            targets=List.of(created);
        }else if(!plugin.partySettings().animateExisting())return "该实体已有其他插件的模型，本服禁止叠加动画。";
        final var owned=created;final var handles=targets;
        lease.onClose(()->plugin.onEntity(entity,()->{
            try{for(var handle:handles)try{if(!handle.closed())handle.stop(name);}
                catch(RuntimeException failure){plugin.getLogger().log(java.util.logging.Level.WARNING,"停止手动动画失败",failure);}}
            finally{if(owned!=null)owned.removeOwned();}
        }));
        if(!lease.active())return "动画请求已取消。";
        try{
            if(owned!=null)owned.prime();
            for(var handle:handles)if(handle!=owned&&!handle.once(name,settings)){lease.close();return "模型没有可动画的根骨骼。";}
        }catch(RuntimeException failure){lease.close();throw failure;}
        long start=System.nanoTime();var task=entity.getScheduler().runAtFixedRate(plugin,t->{
            if(!lease.active()||!entity.isValid()||entity.isDead()||(System.nanoTime()-start)/1e9>=settings.duration()+0.1){t.cancel();lease.close();}
        },lease::close,1,1);
        if(task==null)lease.close();else lease.onClose(task::cancel);return null;
    }
}
