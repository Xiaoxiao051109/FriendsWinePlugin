package com.friendswine.tongtou;

import com.friendswine.JellyAnimation;
import java.util.*;
import java.util.function.*;
import kr.toxicity.model.api.animation.*;
import kr.toxicity.model.api.bone.*;
import kr.toxicity.model.api.data.blueprint.*;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** 两个交替进度对象触发持续采样；四元数由连续时钟计算，不在回弹接缝重置旋转。 */
public final class ContinuousDance {
    private ContinuousDance() {}
    public static BlueprintAnimation animation(ModelRenderer renderer, String name, Supplier<DollSnapshot> source,
                                               DoubleSupplier clock, Supplier<AnimationSettings> settings, boolean feetAnchor) {
        Map<BoneName, BlueprintAnimator> bones=new LinkedHashMap<>();
        for (var group:renderer.rendererGroups().values()) {
            var samples=new AnimationProgress[]{new Progress(source,clock,settings,group.getPosition(),feetAnchor),
                    new Progress(source,clock,settings,group.getPosition(),feetAnchor)};
            bones.put(group.name(),new BlueprintAnimator(group.name(),new AnimationKeyframe(samples)));
        }
        if (bones.isEmpty()) throw new IllegalArgumentException("模型没有根骨骼");
        return new BlueprintAnimation(name,AnimationIterator.Type.LOOP,0.1F,false,Map.copyOf(bones),null,
                AnimationProgress.emptyStorage(0.05F));
    }
    public static AnimationModifier modifier() {
        return AnimationModifier.builder().type(AnimationIterator.Type.LOOP).priority(100).override(false).build();
    }
    static final class Progress implements AnimationProgress {
        private final Supplier<DollSnapshot> source;
        private final DoubleSupplier clock;
        private final Supplier<AnimationSettings> settings;
        private final Vector3f pivot;
        private final boolean feetAnchor;
        Progress(Supplier<DollSnapshot> source,DoubleSupplier clock,Supplier<AnimationSettings> settings,Vector3f pivot,boolean feetAnchor) {
            this.source=source;this.clock=clock;this.settings=settings;this.pivot=new Vector3f(pivot);this.feetAnchor=feetAnchor;
        }
        public float time() { return 0.05F; }
        public boolean skipInterpolation() { return false; }
        public boolean globalRotation() { return false; }
        public BoneMovement animate(BoneMovement base,BoneMovement dest) {
            dest.set(base);
            DollSnapshot doll=source.get();
            if (doll==null || !doll.mode().playing()) return dest;
            double tick=clock.getAsDouble();var config=settings.get();
            float q=JellyAnimation.musicSquash(doll.seconds(tick));
            float x=JellyAnimation.widthScale(q,config.width()), y=JellyAnimation.heightScale(q,config.compression());
            double turns=-doll.rotationSeconds(tick)/JellyAnimation.LENGTH*config.rotationSpeed();
            double yaw=doll.mode().rotating()?turns*360:0;
            var rotation=new Quaternionf().rotationY((float)Math.toRadians(yaw%360));
            if (feetAnchor) dest.position().add(new Vector3f(pivot).mul(x,y,1).rotate(rotation).sub(pivot));
            dest.scale().mul(x,y,1);
            dest.rotation().mul(rotation);
            dest.rawRotation().add(0,(float)yaw,0);
            return dest;
        }
    }
}
