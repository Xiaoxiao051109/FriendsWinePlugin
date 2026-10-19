package com.friendswine.tongtou;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.TradeSelectEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

/** 只追加本插件自己的两条报价，窗口、扣费和共享库存仍由原版处理。 */
public final class NitwitTradeService implements Listener {
    static final int STOCK=16;
    private final FriendsWinePlugin plugin;
    private final NamespacedKey dayKey;
    private final Set<UUID> pendingRefresh=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public NitwitTradeService(FriendsWinePlugin plugin){this.plugin=plugin;dayKey=DataKeys.key("nitwit_day");}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void interact(PlayerInteractEntityEvent event){
        if(!(event.getRightClicked() instanceof Villager villager) || event.getHand()!=EquipmentSlot.HAND
                || event.getPlayer().isSneaking()
                || event.getPlayer().getInventory().getItemInMainHand().getType()==Material.VILLAGER_SPAWN_EGG)return;
        if(!Bukkit.isOwnedByCurrentRegion(villager)||!Bukkit.isOwnedByCurrentRegion(event.getPlayer())){
            event.setCancelled(true);plugin.tell(event.getPlayer(),"村民正在跨区域移动，请稍后重试。");return;
        }
        if(!eligible(villager)||villager.isTrading())return;
        ensure(villager);restock(villager);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void trade(TradeSelectEvent event){
        if(event.getMerchant() instanceof Villager villager && Bukkit.isOwnedByCurrentRegion(villager)
                && eligible(villager))restock(villager);
    }
    public void onVillagerTick(Villager villager){
        if(pendingRefresh.contains(villager.getUniqueId()))refresh(villager);
        if(eligible(villager)&&villager.getPersistentDataContainer().has(dayKey,PersistentDataType.LONG))restock(villager);
    }
    private boolean eligible(Villager v){return v.isValid()&&!v.isDead()&&v.isAdult()
            &&v.getProfession()==Villager.Profession.NITWIT&&!v.isSleeping();}
    private void ensure(Villager v){
        var recipes=new ArrayList<>(v.getRecipes());boolean changed=false;
        for(var kind:List.of(ModeItem.Kind.DOLL,ModeItem.Kind.REMOTE)){
            if(recipes.stream().anyMatch(r->ours(r,kind)))continue;
            MerchantRecipe recipe=new MerchantRecipe(plugin.items().create(kind),0,STOCK,false,0,0F,0,0,true);
            recipe.addIngredient(new ItemStack(Material.EMERALD,price(kind)));recipes.add(recipe);changed=true;
        }
        if(changed)v.setRecipes(recipes);
        if(!v.getPersistentDataContainer().has(dayKey,PersistentDataType.LONG))
            v.getPersistentDataContainer().set(dayKey,PersistentDataType.LONG,plugin.day());
    }
    private void restock(Villager villager){
        var pdc=villager.getPersistentDataContainer();long day=plugin.day();
        Long previous=pdc.get(dayKey,PersistentDataType.LONG);if(previous==null)return;
        if(day==previous)return;
        if(RestockPolicy.refill(previous,day)){
            for(int i=0;i<villager.getRecipeCount();i++){
                var recipe=villager.getRecipe(i);
                if(ours(recipe)){recipe.setUses(0);villager.setRecipe(i,recipe);}
            }
        }
        pdc.set(dayKey,PersistentDataType.LONG,day);
        refresh(villager);
    }
    private void refresh(Villager villager){
        if(villager.getTrader() instanceof Player buyer && !plugin.nativeBridge().refreshTrades(villager,buyer))
            pendingRefresh.add(villager.getUniqueId());
        else pendingRefresh.remove(villager.getUniqueId());
    }
    @EventHandler public void removed(com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent event){pendingRefresh.remove(event.getEntity().getUniqueId());}
    private static int price(ModeItem.Kind kind){return kind==ModeItem.Kind.DOLL?8:4;}
    private boolean ours(MerchantRecipe r){return ours(r,ModeItem.Kind.DOLL)||ours(r,ModeItem.Kind.REMOTE);}
    private boolean ours(MerchantRecipe r,ModeItem.Kind kind){
        var ingredients=r.getIngredients();
        return plugin.items().identify(r.getResult())==kind && r.getResult().getAmount()==1
                &&r.getMaxUses()==STOCK&&r.getPriceMultiplier()==0F&&r.getVillagerExperience()==0
                &&!r.hasExperienceReward()&&r.shouldIgnoreDiscounts()&&ingredients.size()==1
                &&ingredients.getFirst().getType()==Material.EMERALD&&ingredients.getFirst().getAmount()==price(kind)
                &&!ingredients.getFirst().hasItemMeta();
    }
}
