package com.friendswine.tongtou;

import java.util.function.*;
import org.bukkit.util.Vector;

/** 平地连续切向运动；台阶和受阻圆弧交给原生寻路，不穿墙或传送。 */
final class OrbitMotion {
    private static final double STEP=2*Math.PI/400;
    private OrbitMotion() {}
    record Frame(PartySlots.Target target,Vector velocity,boolean advancing,boolean needsPath) {}
    static PartySlots.Target anchor(WorldPoint center,PartySlots.Target current,double width,Predicate<PartySlots.Target> safe){
        double x=current.x()-center.x(),z=current.z()-center.z(),radius=Math.hypot(x,z);
        double minimum=0.5+width/2+0.25,maximum=5-width/2;
        if(radius<minimum||radius>6||maximum<minimum)return null;
        double selected=Math.min(radius,maximum);
        var target=new PartySlots.Target(center.x()+x*selected/radius,current.y(),center.z()+z*selected/radius);
        return safe.test(target)?target:null;
    }
    static Frame advance(WorldPoint center,PartySlots.Target current,PartySlots.Target target,Vector motion,
                         double speed,long elapsed,Function<PartySlots.Target,PartySlots.Target> terrain){
        double angle=STEP*speed*Math.clamp(elapsed,1,5);
        var next=terrain.apply(target.orbit(center,angle));
        if(next==null){
            if(speed>0)for(int ahead:new int[]{4,8,12,20,30,40}){
                var goal=terrain.apply(target.orbit(center,Math.min(angle+STEP*speed*ahead,Math.PI/3)));
                if(goal!=null)return path(goal,motion);
            }
            return new Frame(target,new Vector(0,motion.getY(),0),false,false);
        }
        if(Math.abs(next.y()-current.y())>0.1)return path(next,motion);
        double vx=next.x()-target.x()+(target.x()-current.x())*0.35;
        double vz=next.z()-target.z()+(target.z()-current.z())*0.35;
        var correction=new Vector(vx-motion.getX(),0,vz-motion.getZ());double limit=0.1*Math.max(1,speed);
        if(correction.lengthSquared()>limit*limit)correction.normalize().multiply(limit);
        var velocity=motion.clone().add(correction);
        var actualNext=terrain.apply(new PartySlots.Target(current.x()+velocity.getX(),current.y(),current.z()+velocity.getZ()));
        if(actualNext==null||Math.abs(actualNext.y()-current.y())>0.1)return path(next,motion);
        return new Frame(next,velocity,true,false);
    }
    static boolean shouldJump(PartySlots.Target current,PartySlots.Target target,boolean onGround){
        double dy=target.y()-current.y(),x=target.x()-current.x(),z=target.z()-current.z();
        return onGround&&dy>0.4&&dy<=1.001&&x*x+z*z<=2.25;
    }
    private static Frame path(PartySlots.Target target,Vector motion){return new Frame(target,motion.clone(),true,true);}
}
