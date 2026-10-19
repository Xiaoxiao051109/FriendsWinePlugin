package com.friendswine.tongtou;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Function;

/** 稳定六角站位，只共享占位值；有效地形由生物所属线程检查。 */
public final class PartySlots {
    public record Target(double x,double y,double z) {
        public double squared(Target other){double a=x-other.x,b=y-other.y,c=z-other.z;return a*a+b*b+c*c;}
        public Target orbit(WorldPoint center,double step){
            double dx=x-center.x(),dz=z-center.z();
            return new Target(center.x()+dx*Math.cos(step)+dz*Math.sin(step),y,center.z()+dz*Math.cos(step)-dx*Math.sin(step));
        }
    }
    private record Occupant(Target target,double width,double height) {}
    private final Map<UUID,Map<UUID,Occupant>> groups=new ConcurrentHashMap<>();
    public Target allocate(UUID source,UUID mob,WorldPoint center,Target current,double width,double height,Predicate<Target> safe) {
        return allocateResolved(source,mob,center,current,width,height,p->safe.test(p)?p:null);
    }
    public Target allocateResolved(UUID source,UUID mob,WorldPoint center,Target current,double width,double height,Function<Target,Target> surface) {
        var group=groups.computeIfAbsent(source,k->new HashMap<>());
        synchronized(group){
            Target best=null;double bestSpace=-1,bestTravel=Double.POSITIVE_INFINITY;double radius=5-width/2;
            for(int row=-4;row<=4;row++)for(int col=-4;col<=4;col++){
                double dx=(col+((row&1)==0?0:0.5))*1.25,dz=row*1.25*Math.sqrt(3)/2;
                if(dx*dx+dz*dz>radius*radius || radius<=0)continue;
                Target target=null;
                for(int dy:new int[]{0,1,-1,2,-2}){
                    var candidate=new Target(center.x()+dx,Math.floor(center.y())+dy,center.z()+dz);
                    // 为玩偶自身预留中心空间。
                    if(Math.abs(dx)<0.5+width/2+0.25 && Math.abs(dz)<0.5+width/2+0.25)continue;
                    var resolved=surface.apply(candidate);if(resolved!=null){target=resolved;break;}
                }
                if(target==null)continue;
                double space=dx*dx+dz*dz;boolean occupied=false;
                for(var entry:group.entrySet()){
                    if(entry.getKey().equals(mob))continue;var other=entry.getValue();
                    double gap=width/2+other.width/2+0.25+0.36;
                    if(Math.abs(target.x-other.target.x)<gap && Math.abs(target.z-other.target.z)<gap
                            && target.y<other.target.y+other.height && target.y+height>other.target.y){occupied=true;break;}
                    double x=target.x-other.target.x,z=target.z-other.target.z;space=Math.min(space,x*x+z*z);
                }
                if(occupied)continue;double travel=target.squared(current);
                if(space>bestSpace+1e-6 || Math.abs(space-bestSpace)<=1e-6&&travel<bestTravel){best=target;bestSpace=space;bestTravel=travel;}
            }
            if(best!=null)group.put(mob,new Occupant(best,width,height));else group.remove(mob);
            return best;
        }
    }
    public void update(UUID source,UUID mob,Target at,double width,double height){
        var group=groups.computeIfAbsent(source,k->new HashMap<>());synchronized(group){group.put(mob,new Occupant(at,width,height));}
    }
    public void release(UUID source,UUID mob){var group=groups.get(source);if(group!=null)synchronized(group){group.remove(mob);}}
    public void prune(Set<UUID> sources){groups.keySet().removeIf(id->!sources.contains(id));}
    public void clear(){groups.clear();}
}
