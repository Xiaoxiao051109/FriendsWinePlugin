package com.friendswine.tongtou;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class ModeItem {
    public enum Kind {
        MODE("mode","通透模式"), DOLL("doll","朋友的酒玩偶"), REMOTE("remote","朋友的酒遥控器");
        public final String path, title;
        Kind(String path,String title) { this.path=path;this.title=title; }
        public String id() { return "tongtou:"+path; }
        public static Kind parse(String path) {
            for(var kind:values()) if(kind.path.equalsIgnoreCase(path)) return kind;
            return null;
        }
    }
    private final NamespacedKey marker, kindKey;
    private final String modeId;
    public ModeItem(JavaPlugin plugin,String id) {
        marker=DataKeys.key("mode");kindKey=DataKeys.key("item_kind");modeId=id;
    }
    public boolean matches(ItemStack stack) { return identify(stack)==Kind.MODE; }
    public Kind identify(ItemStack stack) {
        if (stack==null || stack.getType().isAir()) return null;
        if (Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
            var key=net.momirealms.craftengine.bukkit.api.CraftEngineItems.getCustomItemId(stack);
            if(key!=null) {
                if(key.toString().equals(modeId))return Kind.MODE;
                for(var kind:Kind.values())if(key.toString().equals(kind.id()))return kind;
                return null;
            }
        }
        String type=stack.getItemMeta().getPersistentDataContainer().get(kindKey,PersistentDataType.STRING);
        if(type!=null)return Kind.parse(type);
        return stack.getItemMeta().getPersistentDataContainer().getOrDefault(marker,PersistentDataType.BYTE,(byte)0)==1?Kind.MODE:null;
    }
    public ItemStack create() { return create(Kind.MODE); }
    public ItemStack create(Kind kind) {
        ItemStack stack=null;
        if(Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
            var definition=net.momirealms.craftengine.bukkit.api.CraftEngineItems.byId(kind==Kind.MODE?modeId:kind.id());
            if(definition!=null)stack=definition.buildBukkitItem();
        }
        if(stack==null) {
            if(kind!=Kind.MODE)throw new IllegalStateException("CE尚未加载物品："+kind.id());
            stack=new ItemStack(Material.AMETHYST_SHARD);
        }
        stack.editMeta(meta->{
            meta.displayName(Component.text(kind.title));
            if(kind!=Kind.DOLL)meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(kindKey,PersistentDataType.STRING,kind.path);
            if(kind==Kind.MODE)meta.getPersistentDataContainer().set(marker,PersistentDataType.BYTE,(byte)1);
        });
        return stack;
    }
}
