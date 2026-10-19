package com.friendswine.tongtou;

import java.util.Objects;
import org.bukkit.NamespacedKey;

/** 持久化格式独立于插件显示名；沿用旧namespace，原有玩偶ID、遥控和报价不会失效。 */
final class DataKeys {
    private DataKeys() {}
    static NamespacedKey key(String path){return Objects.requireNonNull(NamespacedKey.fromString("tongtoumode:"+path));}
}
