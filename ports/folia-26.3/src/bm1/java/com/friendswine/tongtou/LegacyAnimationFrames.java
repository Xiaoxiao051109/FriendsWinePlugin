package com.friendswine.tongtou;

import java.util.*;
import java.util.function.*;
import kr.toxicity.model.api.animation.*;
import kr.toxicity.model.api.bone.BoneName;
import kr.toxicity.model.api.data.blueprint.*;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import org.joml.Vector3f;

final class LegacyAnimationFrames {
    private LegacyAnimationFrames() {}
    static BlueprintAnimation once(ModelRenderer renderer,String name,AnimationSettings settings){
        Map<BoneName,BlueprintAnimator> bones=new LinkedHashMap<>();
        for(var group:renderer.rendererGroups().values()){
            List<AnimationMovement> frames=new ArrayList<>();int intervals=(int)Math.ceil(settings.duration()*40);double previous=0;
            for(int i=0;i<=intervals;i++){
                double seconds=settings.duration()*i/intervals;var frame=settings.frame(seconds);
                frames.add(movement((float)(seconds-previous),new DancePose(frame.x(),frame.y(),frame.yaw()),group.getPosition(),true));previous=seconds;
            }
            bones.put(group.name(),new BlueprintAnimator(group.name().name(),List.copyOf(frames)));
        }
        return new BlueprintAnimation(name,AnimationIterator.Type.PLAY_ONCE,(float)settings.duration(),false,Map.copyOf(bones),null,AnimationMovement.withEmpty(0.05F));
    }
    static BlueprintAnimation continuous(ModelRenderer renderer,String name,Supplier<DollSnapshot> source,DoubleSupplier clock,Supplier<AnimationSettings> settings,boolean feetAnchor){
        Map<BoneName,BlueprintAnimator> bones=new LinkedHashMap<>();
        for(var group:renderer.rendererGroups().values())bones.put(group.name(),new BlueprintAnimator(group.name().name(),dynamic(source,clock,settings,group.getPosition(),feetAnchor)));
        return new BlueprintAnimation(name,AnimationIterator.Type.LOOP,0.1F,false,Map.copyOf(bones),null,AnimationMovement.withEmpty(0.05F));
    }
    static List<AnimationMovement> dynamic(Supplier<DollSnapshot> source,DoubleSupplier clock,Supplier<AnimationSettings> settings,Vector3f pivot,boolean feetAnchor){
        Vector3f anchor=new Vector3f(pivot);
        // 旧API的LOOP迭代器直接调用List.get；每次读取在BM动画线程按绝对时钟生成新帧，不修改共享模型、不重播。
        return new AbstractList<>() {
            public int size(){return 2;}
            public AnimationMovement get(int index){Objects.checkIndex(index,2);return movement(0.05F,DancePose.sample(source.get(),clock.getAsDouble(),settings.get()),anchor,feetAnchor);}
        };
    }
    static AnimationMovement movement(float time,DancePose pose,Vector3f pivot,boolean feetAnchor){
        var position=feetAnchor?new Vector3f(pivot).mul(pose.x(),pose.y(),1).rotateY((float)Math.toRadians(pose.yaw()%360)).sub(pivot):new Vector3f();
        return new AnimationMovement(time,position,new Vector3f(pose.x()-1,pose.y()-1,0),new Vector3f(0,(float)(pose.yaw()%360),0),false,false);
    }
}
