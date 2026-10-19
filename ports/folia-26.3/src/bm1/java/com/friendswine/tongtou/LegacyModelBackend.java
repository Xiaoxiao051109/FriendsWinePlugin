package com.friendswine.tongtou;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.BetterModelPlugin.ReloadResult;
import kr.toxicity.model.api.animation.*;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.manager.ReloadInfo;
import kr.toxicity.model.api.tracker.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

/** 1.21.x专用BM1.15.2后端，不经过CE的BM3外部模型桥。 */
public final class LegacyModelBackend implements ModelBackend {
    public LegacyModelBackend(FriendsWinePlugin plugin){}
    public boolean hasModel(String name){return BetterModel.modelOrNull(name)!=null;}
    public List<Handle> existing(Entity entity){var r=BetterModel.registryOrNull(entity.getUniqueId());return r==null?List.of():r.trackers().stream().filter(t->!t.isClosed()).map(t->(Handle)new Bound(t,false)).toList();}
    public Handle find(Entity entity,String name){var r=BetterModel.registryOrNull(entity.getUniqueId());var t=r==null?null:r.tracker(name);return t==null||t.isClosed()?null:new Bound(t,false);}
    public Handle attach(Entity entity,String name,boolean temporary,Consumer<Handle> prepare){
        var renderer=Objects.requireNonNull(BetterModel.modelOrNull(name),"模型不存在："+name);
        if(temporary)renderer=new ModelRenderer(renderer.name(),ModelRenderer.Type.PLAYER,renderer.rendererGroups(),renderer.animations());
        var tracker=renderer.create(entity,new TrackerModifier(false,false,false),t->{t.hideOption(EntityHideOption.DEFAULT);prepare.accept(new Bound(t,true));});
        return new Bound(tracker,true);
    }
    public AutoCloseable subscribe(BooleanSupplier enabled,Runnable starting,Consumer<Reload> completed){
        var active=new AtomicBoolean(true);
        BetterModel.plugin().addReloadStartHandler(zip->{if(active.get()&&enabled.getAsBoolean())starting.run();});
        BetterModel.plugin().addReloadEndHandler(result->{if(active.get()&&enabled.getAsBoolean())completed.accept(convert(result));});
        return ()->active.set(false);
    }
    public Reload reload(){return convert(BetterModel.plugin().reload(new ReloadInfo(true,Bukkit.getConsoleSender())));}
    private static Reload convert(ReloadResult result){
        if(result instanceof ReloadResult.Failure f)return Reload.failed(f.throwable());
        if(result instanceof ReloadResult.Success s){var p=s.packResult();return new Reload(p.directory()==null?null:p.directory().toPath(),p.hash().toString(),null,false);}
        return Reload.loading();
    }
    private record Bound(EntityTracker tracker,boolean owned) implements Handle {
        public String model(){return tracker.renderer().name();}
        public boolean closed(){return tracker.isClosed();}
        public boolean temporary(){return tracker.renderer().type()==ModelRenderer.Type.PLAYER;}
        public boolean same(Handle other){return other instanceof Bound b&&b.tracker==tracker;}
        public boolean once(String name,AnimationSettings settings){return tracker.animate(LegacyAnimationFrames.once(tracker.renderer(),name,settings),AnimationModifier.builder().type(AnimationIterator.Type.PLAY_ONCE).override(false).build());}
        public boolean continuous(String name,Supplier<DollSnapshot> source,DoubleSupplier clock,Supplier<AnimationSettings> settings,boolean feetAnchor){return tracker.animate(LegacyAnimationFrames.continuous(tracker.renderer(),name,source,clock,settings,feetAnchor),AnimationModifier.builder().type(AnimationIterator.Type.LOOP).override(false).build());}
        public void stop(String name){tracker.stopAnimation(name);}
        public void prime(){
            if(closed())return;var registry=tracker.registry();var packets=BetterModel.nms().createBundler(tracker.bones().size()*2+1);
            for(var bone:tracker.bones())bone.forceTransformation(packets);
            for(var bone:tracker.bones())bone.forceUpdate(packets);
            if(packets.isEmpty())return;
            registry.viewedPlayer().forEach(channel->{
                if(!closed()&&!registry.isClosed()&&tracker.isSpawned(channel.player())&&!tracker.isHide(channel.player())){
                    BetterModel.nms().hide(channel,registry);packets.send(channel.player());
                }
            });
        }
        public void removeOwned(){if(owned&&!tracker.registry().isClosed()&&tracker.registry().tracker(model())==tracker)tracker.registry().remove(model());}
    }
}
