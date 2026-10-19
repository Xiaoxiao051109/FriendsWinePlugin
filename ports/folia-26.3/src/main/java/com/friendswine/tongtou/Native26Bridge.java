package com.friendswine.tongtou;

import java.lang.reflect.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.*;

/** 按实际签名适配1.21与26.x缺少公开API的Brain攻击记忆和原生交易菜单同步。 */
public final class Native26Bridge {
    private final Object attackMemory;
    private final Method getHandle, getBrain, erase, hasMemory, getMemory, bukkitEntity, playerInventory;
    private final Method craftPlayerHandle, craftVillagerHandle, offers, villagerData, level, xp, showProgress, canRestock;
    private final Field containerMenu, containerId;
    private final Method sendOffers, broadcast, slotsChanged;
    public Native26Bridge() {
        try {
            Class<?> mob=Class.forName("org.bukkit.craftbukkit.entity.CraftMob");
            getHandle=mob.getMethod("getHandle");
            Class<?> living=Class.forName("net.minecraft.world.entity.LivingEntity");
            getBrain=living.getMethod("getBrain");
            bukkitEntity=Class.forName("net.minecraft.world.entity.Entity").getMethod("getBukkitEntity");
            Class<?> memory=Class.forName("net.minecraft.world.entity.ai.memory.MemoryModuleType");
            attackMemory=memory.getField("ATTACK_TARGET").get(null);
            Class<?> brain=Class.forName("net.minecraft.world.entity.ai.Brain");
            erase=brain.getMethod("eraseMemory",memory); hasMemory=brain.getMethod("hasMemoryValue",memory);
            getMemory=brain.getMethod("getMemory",memory);
            craftPlayerHandle=Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
            craftVillagerHandle=Class.forName("org.bukkit.craftbukkit.entity.CraftVillager").getMethod("getHandle");
            Class<?> player=Class.forName("net.minecraft.server.level.ServerPlayer");
            playerInventory=player.getMethod("getInventory");
            Class<?> offersType=Class.forName("net.minecraft.world.item.trading.MerchantOffers");
            sendOffers=player.getMethod("sendMerchantOffers",int.class,offersType,int.class,int.class,boolean.class,boolean.class);
            containerMenu=Class.forName("net.minecraft.world.entity.player.Player").getField("containerMenu");
            Class<?> menu=Class.forName("net.minecraft.world.inventory.AbstractContainerMenu");
            containerId=menu.getField("containerId"); broadcast=menu.getMethod("broadcastChanges");
            slotsChanged=menu.getMethod("slotsChanged",Class.forName("net.minecraft.world.Container"));
            Class<?> villager=findClass("net.minecraft.world.entity.npc.villager.Villager","net.minecraft.world.entity.npc.Villager");
            offers=villager.getMethod("getOffers");villagerData=villager.getMethod("getVillagerData");
            level=findMethod(villagerData.getReturnType(),"level","getLevel");
            xp=villager.getMethod("getVillagerXp");showProgress=villager.getMethod("showProgressBar");canRestock=villager.getMethod("canRestock");
        } catch(ReflectiveOperationException failure) { throw new IllegalStateException("原生接口自检失败，拒绝静默禁用和平/交易同步",failure); }
    }
    static Class<?> findClass(String... candidates)throws ClassNotFoundException {
        ClassNotFoundException last=null;for(String name:candidates)try{return Class.forName(name);}catch(ClassNotFoundException failure){last=failure;}
        throw last;
    }
    static Method findMethod(Class<?> type,String... candidates)throws NoSuchMethodException {
        NoSuchMethodException last=null;for(String name:candidates)try{return type.getMethod(name);}catch(NoSuchMethodException failure){last=failure;}
        throw last;
    }
    public void clearAttackMemory(Mob mob, java.util.function.Predicate<Entity> protectedTarget) {
        if(!Bukkit.isOwnedByCurrentRegion(mob))throw new IllegalStateException("Brain操作线程归属错误");
        try {
            Object brain=getBrain.invoke(getHandle.invoke(mob));
            if(!(boolean)hasMemory.invoke(brain,attackMemory))return;
            var target=(Optional<?>)getMemory.invoke(brain,attackMemory);
            if(target.isEmpty())return;
            Object handle=target.get();
            Entity bukkit=(Entity)bukkitEntity.invoke(handle);
            if(protectedTarget.test(bukkit))erase.invoke(brain,attackMemory);
        } catch(ReflectiveOperationException failure) { throw new IllegalStateException("清理攻击记忆失败",failure); }
    }
    public boolean refreshTrades(Villager villager,Player player) {
        if(!Bukkit.isOwnedByCurrentRegion(villager)||!Bukkit.isOwnedByCurrentRegion(player))return false;
        if(villager.getTrader()!=player)return true;
        try {
            Object p=craftPlayerHandle.invoke(player), v=craftVillagerHandle.invoke(villager), menu=containerMenu.get(p);
            if(!menu.getClass().getSimpleName().equals("MerchantMenu"))return true;
            slotsChanged.invoke(menu,playerInventory.invoke(p));
            sendOffers.invoke(p,containerId.getInt(menu),offers.invoke(v),level.invoke(villagerData.invoke(v)),xp.invoke(v),
                    showProgress.invoke(v),canRestock.invoke(v));
            broadcast.invoke(menu);return true;
        } catch(ReflectiveOperationException failure) { throw new IllegalStateException("同步交易报价失败",failure); }
    }
}
