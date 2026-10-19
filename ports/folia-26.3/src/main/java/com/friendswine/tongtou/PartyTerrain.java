package com.friendswine.tongtou;

import java.util.*;
import java.util.function.Predicate;
import org.bukkit.*;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.*;
import org.bukkit.util.BoundingBox;

/** 所有调用均发生在生物所属线程，先验证整个盒子的区块归属和加载状态。 */
final class PartyTerrain {
    private PartyTerrain() {}
    static boolean water(Mob mob){return mob instanceof WaterMob || mob instanceof Guardian;}
    static boolean flying(Mob mob){return switch(mob.getType().getKey().getKey()){
        case "ghast","phantom","happy_ghast","bat","bee","parrot","allay","vex"->true;default->false;
    };}
    static boolean safe(Mob mob,PartySlots.Target at) {
        World world=mob.getWorld();double width=mob.getWidth(),height=mob.getHeight();
        if(at.y()<world.getMinHeight() || at.y()+height>world.getMaxHeight())return false;
        var box=new BoundingBox(at.x()-width/2,at.y(),at.z()-width/2,at.x()+width/2,at.y()+height,at.z()+width/2);
        var support=box.clone().shift(0,-0.1,0);
        if(!available(world,box)||!available(world,support)||!world.getWorldBorder().isInside(new Location(world,box.getMinX(),at.y(),box.getMinZ()))
                ||!world.getWorldBorder().isInside(new Location(world,box.getMaxX(),at.y(),box.getMaxZ())))return false;
        if(collides(world,box))return false;
        var feet=world.getBlockAt((int)Math.floor(at.x()),(int)Math.floor(at.y()),(int)Math.floor(at.z()));
        if(danger(feet.getType())||danger(feet.getRelative(0,-1,0).getType()))return false;
        if(feet.getRelative(0,-1,0).getBlockData() instanceof Lightable lit && lit.isLit()
                && feet.getRelative(0,-1,0).getType().name().contains("CAMPFIRE"))return false;
        if(water(mob))return feet.getType()==Material.WATER;
        return flying(mob)||collides(world,support);
    }
    static PartySlots.Target walkable(Mob mob,PartySlots.Target at){
        if(safe(mob,at))return at;
        if(water(mob)||flying(mob))return null;
        World world=mob.getWorld();double half=mob.getWidth()/2;
        var area=new BoundingBox(at.x()-half,Math.max(world.getMinHeight(),at.y()-2),at.z()-half,
                at.x()+half,Math.min(world.getMaxHeight(),at.y()+mob.getHeight()+2),at.z()+half);
        if(!available(world,area))return null;
        Set<Double> heights=new HashSet<>();
        int x0=(int)Math.floor(area.getMinX()),x1=(int)Math.floor(area.getMaxX()-1e-7);
        int z0=(int)Math.floor(area.getMinZ()),z1=(int)Math.floor(area.getMaxZ()-1e-7);
        int y0=(int)Math.floor(area.getMinY()),y1=Math.min(world.getMaxHeight()-1,(int)Math.floor(at.y()+1));
        for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)
            for(var box:world.getBlockAt(x,y,z).getCollisionShape().getBoundingBoxes())
                if(box.getMaxX()+x>area.getMinX()&&box.getMinX()+x<area.getMaxX()
                        &&box.getMaxZ()+z>area.getMinZ()&&box.getMinZ()+z<area.getMaxZ())heights.add(box.getMaxY()+y);
        return surface(at,heights,p->safe(mob,p));
    }
    static PartySlots.Target surface(PartySlots.Target at,Collection<Double> heights,Predicate<PartySlots.Target> safe){
        return heights.stream().filter(y->Double.isFinite(y)&&y>=at.y()-1.001&&y<=at.y()+1.001)
                .sorted(Comparator.comparingDouble((Double y)->Math.abs(y-at.y())).thenComparingDouble(Double::doubleValue))
                .map(y->new PartySlots.Target(at.x(),y,at.z())).filter(safe).findFirst().orElse(null);
    }
    static boolean available(World world,BoundingBox box){
        int x0=(int)Math.floor(box.getMinX())>>4,x1=(int)Math.floor(box.getMaxX())>>4;
        int z0=(int)Math.floor(box.getMinZ())>>4,z1=(int)Math.floor(box.getMaxZ())>>4;
        for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)if(!Bukkit.isOwnedByCurrentRegion(world,x,z)||!world.isChunkLoaded(x,z))return false;
        return true;
    }
    private static boolean collides(World world,BoundingBox box){
        int x0=(int)Math.floor(box.getMinX()),x1=(int)Math.floor(box.getMaxX()-1e-7);
        int y0=(int)Math.floor(box.getMinY()),y1=(int)Math.floor(box.getMaxY()-1e-7);
        int z0=(int)Math.floor(box.getMinZ()),z1=(int)Math.floor(box.getMaxZ()-1e-7);
        for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)
            for(var shape:world.getBlockAt(x,y,z).getCollisionShape().getBoundingBoxes())
                if(shape.clone().shift(x,y,z).overlaps(box))return true;
        return false;
    }
    private static boolean danger(Material type){return switch(type){
        case MAGMA_BLOCK,CACTUS,FIRE,SOUL_FIRE,LAVA,POWDER_SNOW,SWEET_BERRY_BUSH,WITHER_ROSE->true;default->false;
    };}
}
