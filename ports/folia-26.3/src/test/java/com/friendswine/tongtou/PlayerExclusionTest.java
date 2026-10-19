package com.friendswine.tongtou;

import java.lang.reflect.Proxy;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerExclusionTest {
    @Test void playerIsRejectedBeforeReadingAnyPlayerState() {
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> { throw new AssertionError("不应读取玩家状态：" + method.getName()); });
        assertThrows(IllegalArgumentException.class, () -> Appearance.capture(player));
        var book = new SessionBook<String>(1);
        var lease = book.acquire("player");
        assertEquals("玩家不参与通透动画。", new BetterModelAnimations(null).play(player, lease,
                new AnimationSettings(1, 50, 50, 1)));
        lease.close();
    }
}
