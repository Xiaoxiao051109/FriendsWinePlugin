package com.friendswine.tongtou;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

public final class RemoteService implements Listener {
    private final FriendsWinePlugin plugin;
    private final PartyIndex index;
    private final NamespacedKey idKey,worldKey,positionKey;
    private final Map<UUID,Long> clicks=new ConcurrentHashMap<>();
    public RemoteService(FriendsWinePlugin plugin,PartyIndex index){
        this.plugin=plugin;this.index=index;idKey=DataKeys.key("remote_target");worldKey=DataKeys.key("remote_world");positionKey=DataKeys.key("remote_position");
    }
    public void bind(Player player,DollSnapshot doll){
        plugin.onEntity(player,()->{
            var held=player.getInventory().getItemInMainHand();
            if(!player.hasPermission("tongtou.party.use") || plugin.items().identify(held)!=ModeItem.Kind.REMOTE)return;
            if(!player.getWorld().getUID().equals(doll.point().world()))return;
            held.editMeta(meta->{
                var data=meta.getPersistentDataContainer();
                data.set(idKey,PersistentDataType.STRING,doll.id().toString());
                data.set(worldKey,PersistentDataType.STRING,doll.point().world().toString());
                data.set(positionKey,PersistentDataType.LONG,doll.packedPosition());
            });
            player.startUsingItem(EquipmentSlot.HAND);
            plugin.tell(player,"已绑定玩偶："+(int)Math.floor(doll.point().x())+", "+(int)Math.floor(doll.point().y())+", "+(int)Math.floor(doll.point().z()));
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void air(PlayerInteractEvent event){
        Player player=event.getPlayer();
        if(event.getHand()!=EquipmentSlot.HAND || event.getAction()!=Action.RIGHT_CLICK_AIR
                || !player.hasPermission("tongtou.party.use") || plugin.items().identify(player.getInventory().getItemInMainHand())!=ModeItem.Kind.REMOTE)return;
        if(player.hasActiveItem())return;
        long now=System.nanoTime();Long previous=clicks.put(player.getUniqueId(),now);
        if(previous!=null && now-previous<250_000_000L)return;
        // 长使用时间组件配合原版释放按键包，按住右键不会不断切档。
        player.startUsingItem(EquipmentSlot.HAND);
        var held=player.getInventory().getItemInMainHand();
        var data=held.getItemMeta().getPersistentDataContainer();
        String world=data.get(worldKey,PersistentDataType.STRING),id=data.get(idKey,PersistentDataType.STRING);
        if(world==null || id==null){plugin.tell(player,"请先右击玩偶进行绑定。");return;}
        if(!world.equals(player.getWorld().getUID().toString())){plugin.tell(player,"绑定的玩偶位于其他维度。");return;}
        UUID target;
        try{target=UUID.fromString(id);}catch(IllegalArgumentException invalid){plugin.tell(player,"遥控绑定无效，请重新绑定。");return;}
        var snapshot=index.get(target);
        if(snapshot==null){plugin.tell(player,"玩偶已移除或所在区块未加载。");return;}
        if(!snapshot.point().world().toString().equals(world)){plugin.tell(player,"绑定的玩偶已移动至其他维度，请重新绑定。");return;}
        plugin.dolls().cycle(snapshot,player);
    }
    @EventHandler public void consumed(PlayerItemConsumeEvent event){
        if(plugin.items().identify(event.getItem())==ModeItem.Kind.REMOTE)event.setCancelled(true);
    }
    @EventHandler public void quit(PlayerQuitEvent event){clicks.remove(event.getPlayer().getUniqueId());}
}
