package com.friendswine.tongtou;

import java.util.*;
import kr.toxicity.model.api.nms.PacketBundler;
import kr.toxicity.model.api.platform.PlatformPlayer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FirstFramePacketsTest {
    private static final class Packets implements PacketBundler {
        final List<String> packets = new ArrayList<>();
        @Override public boolean isEmpty() { return packets.isEmpty(); }
        @Override public int size() { return packets.size(); }
        @Override public void send(PlatformPlayer viewer, Runnable done) { throw new AssertionError("离线测试不访问网络"); }
    }
    @Test void readyFrameIsSentImmediatelyAfterHideAndInSingleBundle() {
        var packets = new Packets();
        List<String> order = new ArrayList<>();
        var bone = new FirstFramePackets.Bone(
                bundle -> { assertSame(packets, bundle); packets.packets.add("transform"); order.add("transform"); },
                bundle -> { assertSame(packets, bundle); packets.packets.add("item-and-metadata"); order.add("metadata"); });
        var viewer = new FirstFramePackets.Viewer(() -> true,
                () -> order.add("hide-original"), bundle -> {
                    assertSame(packets, bundle);
                    assertEquals(List.of("transform", "item-and-metadata"), packets.packets);
                    order.add("send-ready-frame");
                });
        FirstFramePackets.deliver(packets, List.of(bone), List.of(viewer));
        assertEquals(List.of("transform", "metadata", "hide-original", "send-ready-frame"), order,
                "不能隐藏本体后还要等待另一个调度tick才发替身物品数据");
    }
    @Test void allBonesArePreparedBeforeAnyViewerIsHidden() {
        var packets = new Packets();
        var bones = List.of(
                new FirstFramePackets.Bone(b -> packets.packets.add("transform-a"), b -> packets.packets.add("metadata-a")),
                new FirstFramePackets.Bone(b -> packets.packets.add("transform-b"), b -> packets.packets.add("metadata-b")));
        List<String> sends = new ArrayList<>();
        var viewers = List.of(
                new FirstFramePackets.Viewer(() -> true, () -> assertEquals(4, packets.size()), b -> sends.add("a")),
                new FirstFramePackets.Viewer(() -> true, () -> assertEquals(4, packets.size()), b -> sends.add("b")));
        FirstFramePackets.deliver(packets, bones, viewers);
        assertEquals(List.of("transform-a", "transform-b", "metadata-a", "metadata-b"), packets.packets);
        assertEquals(List.of("a", "b"), sends);
    }
    @Test void hiddenUnspawnedOrClosedViewerReceivesNothing() {
        var packets = new Packets();
        var bone = new FirstFramePackets.Bone(b -> packets.packets.add("transform"), b -> packets.packets.add("metadata"));
        var viewer = new FirstFramePackets.Viewer(() -> false,
                () -> fail("被其他插件隐藏的观众不能重新隐藏或展示"), b -> fail("不应发送"));
        FirstFramePackets.deliver(packets, List.of(bone), List.of(viewer));
    }
    @Test void emptyFrameDoesNotHideOriginal() {
        var viewer = new FirstFramePackets.Viewer(() -> true,
                () -> fail("没有替身首帧时不能先隐藏本体"), b -> fail("没有数据不发送"));
        FirstFramePackets.deliver(new Packets(), List.of(), List.of(viewer));
    }
    @Test void preparationFailureDoesNotTouchAnyViewer() {
        var bone = new FirstFramePackets.Bone(b -> { throw new IllegalStateException("无法准备变换"); },
                b -> fail("不应继续准备数据"));
        var viewer = new FirstFramePackets.Viewer(() -> true,
                () -> fail("首帧准备失败不能隐藏"), b -> fail("首帧准备失败不能发送"));
        assertThrows(IllegalStateException.class, () -> FirstFramePackets.deliver(new Packets(), List.of(bone), List.of(viewer)));
    }
    @Test void multipleClickSessionsStillHaveIndependentFrames() {
        var first = new Packets(); var second = new Packets();
        var bone = new FirstFramePackets.Bone(b -> ((Packets) b).packets.add("transform"), b -> ((Packets) b).packets.add("metadata"));
        List<PacketBundler> sent = new ArrayList<>();
        var viewer = new FirstFramePackets.Viewer(() -> true, () -> {}, sent::add);
        FirstFramePackets.deliver(first, List.of(bone), List.of(viewer));
        FirstFramePackets.deliver(second, List.of(bone), List.of(viewer));
        assertEquals(List.of(first, second), sent);
        assertNotSame(sent.get(0), sent.get(1));
    }
}
