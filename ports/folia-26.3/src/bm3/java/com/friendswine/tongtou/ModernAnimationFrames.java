package com.friendswine.tongtou;

import java.util.*;
import kr.toxicity.model.api.animation.*;
import kr.toxicity.model.api.bone.*;
import kr.toxicity.model.api.data.blueprint.*;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import org.joml.Vector3f;

final class ModernAnimationFrames {
    private ModernAnimationFrames() {}
    static BlueprintAnimation animation(ModelRenderer renderer, String name, AnimationSettings settings) {
        Map<BoneName, BlueprintAnimator> bones = new LinkedHashMap<>();
        AnimationKeyframe empty = null;
        for (var group : renderer.rendererGroups().values()) {
            var keyframe = keyframes(settings, group.getPosition());
            bones.put(group.name(), new BlueprintAnimator(group.name(), keyframe));
            empty = keyframe;
        }
        if (empty == null) throw new IllegalArgumentException("模型没有根骨骼");
        return new BlueprintAnimation(name, AnimationIterator.Type.PLAY_ONCE, (float) settings.duration(),
                false, Map.copyOf(bones), null, empty.toEmpty());
    }
    static AnimationKeyframe keyframes(AnimationSettings settings, Vector3f pivot) {
        int intervals = (int) Math.ceil(settings.duration() * 40);
        var builder = AnimationKeyframe.builder(intervals + 1, false);
        double previous = 0;
        for (int i = 0; i <= intervals; i++) {
            double seconds = settings.duration() * i / intervals;
            var frame = settings.frame(seconds);
            var position = new Vector3f(pivot).mul(frame.x(), frame.y(), 1)
                    .rotateY((float) Math.toRadians(frame.yaw())).sub(pivot);
            // 接口接收帧间秒数和缩放增量，内部会将缩放加一。
            builder.write((float) (seconds - previous), position,
                    new Vector3f(frame.x() - 1, frame.y() - 1, 0), new Vector3f(0, frame.yaw(), 0), false);
            previous = seconds;
        }
        return builder.build();
    }

}
