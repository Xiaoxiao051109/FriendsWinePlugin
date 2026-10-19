package com.friendswine.tongtou;

import java.util.List;
import java.util.function.*;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.nms.PacketBundler;
import kr.toxicity.model.api.tracker.EntityTracker;

/** 创建包已经入队后立即补齐首帧，避免等到BetterModel的下一个整tick才显示物品。 */
final class FirstFramePackets {
    private FirstFramePackets() {}
    record Bone(Consumer<PacketBundler> transform, Consumer<PacketBundler> metadata) {}
    record Viewer(BooleanSupplier visible, Runnable hideOriginal, Consumer<PacketBundler> send) {}

    static void prime(EntityTracker tracker) {
        if (tracker.isClosed()) return;
        var registry = tracker.registry();
        // Tracker.bones()已经包含全部骨骼，不重复展开子树。
        var bones = tracker.bones().stream()
                .map(bone -> new Bone(bone::forceTransformation, bone::forceUpdate)).toList();
        var viewers = registry.viewedPlayer().map(channel -> new Viewer(
                () -> !tracker.isClosed() && !registry.isClosed() && tracker.isSpawned(channel.uuid())
                        && !tracker.isHide(channel.player()),
                () -> BetterModel.nms().hide(channel, registry),
                packets -> packets.send(channel.player()))).toList();
        deliver(BetterModel.nms().createBundler(bones.size() * 2 + 1), bones, viewers);
    }

    static void deliver(PacketBundler packets, List<Bone> bones, List<Viewer> viewers) {
        // 同一首帧包中先写变换再写物品数据；本体由BetterModel隐藏，不修改真实隐身属性。
        bones.forEach(bone -> bone.transform().accept(packets));
        bones.forEach(bone -> bone.metadata().accept(packets));
        if (packets.isEmpty()) return;
        for (Viewer viewer : viewers) {
            if (!viewer.visible().getAsBoolean()) continue;
            viewer.hideOriginal().run();
            viewer.send().accept(packets);
        }
    }
}
