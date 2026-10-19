package com.friendswine.tongtou;

import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;

public final class PeaceService implements Listener {
    private final FriendsWinePlugin plugin;
    private final PartyIndex index;
    private final EntityPositions positions;
    public PeaceService(FriendsWinePlugin plugin,PartyIndex index,EntityPositions positions){this.plugin=plugin;this.index=index;this.positions=positions;}
    public boolean protectedPair(Mob attacker,Entity victim){
        if(!(victim instanceof Mob || victim instanceof Player))return false;
        WorldPoint a=positions.point(attacker),b=positions.point(victim);
        return protectedPositions(index,a,b);
    }
    static boolean protectedPositions(PartyIndex index,WorldPoint a,WorldPoint b){
        if(a!=null&&b!=null&&!a.world().equals(b.world()))return false;
        return a!=null&&index.nearest(a,50)!=null || b!=null&&index.nearest(b,50)!=null;
    }
    public void tick(Mob mob){
        if(mob instanceof Creeper || !plugin.partySettings().peace())return;
        positions.capture(mob);
        var target=mob.getTarget();if(target!=null&&protectedPair(mob,target))mob.setTarget(null);
        plugin.nativeBridge().clearAttackMemory(mob,targetEntity->protectedPair(mob,targetEntity));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void target(EntityTargetLivingEntityEvent event){
        if(!plugin.partySettings().peace() || event.getTarget()==null
                || !(event.getEntity() instanceof Mob mob) || mob instanceof Creeper)return;
        if(protectedPair(mob,event.getTarget()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void damage(EntityDamageByEntityEvent event){
        if(!plugin.partySettings().peace())return;
        Entity causing=event.getDamageSource().getCausingEntity();
        if(!(causing instanceof Mob mob))return;
        boolean explosion=event.getCause()==EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                ||event.getCause()==EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
        if(mob instanceof Creeper&&explosion)return;
        if(protectedPair(mob,event.getEntity()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void projectile(ProjectileLaunchEvent event){
        if(event.getEntity().getShooter() instanceof Mob mob)positions.capture(mob);
    }
}
