package com.friendswine.tongtou;

import java.util.*;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrbitMotionTest {
    private final WorldPoint center=new WorldPoint(UUID.randomUUID(),0,64,0);
    private final PartySlots.Target start=new PartySlots.Target(3.5,64,0);
    @Test void actualMotionCompletesCircleDespiteGroundDrag(){
        var current=start;var target=start;var motion=new Vector();double travelled=0;
        for(int tick=0;tick<400;tick++){
            var frame=OrbitMotion.advance(center,current,target,motion,1,1,p->p);
            assertTrue(frame.advancing());assertFalse(frame.needsPath());
            var next=new PartySlots.Target(current.x()+frame.velocity().getX(),64,current.z()+frame.velocity().getZ());
            double angle=Math.atan2(current.x()*next.z()-current.z()*next.x(),current.x()*next.x()+current.z()*next.z());
            travelled+=angle;current=next;target=frame.target();motion=frame.velocity().multiply(0.546);
            assertEquals(3.5,Math.hypot(current.x(),current.z()),0.08);
        }
        assertEquals(-2*Math.PI,travelled,0.05);assertTrue(current.squared(start)<0.02);
    }
    @Test void slabAndCarpetHeightDoNotPreventOrbitStartup(){
        for(double y:new double[]{64,64.0625,64.5,64.9375}){
            var at=new PartySlots.Target(3.5,y,0);
            assertEquals(at,OrbitMotion.anchor(center,at,0.6,p->p.y()==y));
            var frame=OrbitMotion.advance(center,at,at,new Vector(0,-0.0784,0),1,1,p->p);
            assertFalse(frame.needsPath());assertTrue(frame.velocity().getZ()<0);assertEquals(-0.0784,frame.velocity().getY());
        }
    }
    @Test void oneBlockObstacleRequestsNativePathWithoutInventingUpwardVelocity(){
        var motion=new Vector(0,-0.0784,0);
        var frame=OrbitMotion.advance(center,start,start,motion,1,1,p->new PartySlots.Target(p.x(),65,p.z()));
        assertTrue(frame.needsPath());assertEquals(65,frame.target().y());assertEquals(motion,frame.velocity());
        assertTrue(OrbitMotion.shouldJump(start,frame.target(),true));
        assertFalse(OrbitMotion.shouldJump(start,frame.target(),false));
    }
    @Test void tallWallCanUseADetourButFullyBlockedAreaStaysSafe(){
        var detour=OrbitMotion.advance(center,start,start,new Vector(),1,1,p->p.z()<-0.2?p:null);
        assertTrue(detour.needsPath());assertTrue(detour.target().z()<-0.2);
        var blocked=OrbitMotion.advance(center,start,start,new Vector(0.1,-0.0784,0.1),1,1,p->null);
        assertFalse(blocked.advancing());assertFalse(blocked.needsPath());assertEquals(start,blocked.target());
        assertEquals(new Vector(0,-0.0784,0),blocked.velocity());
    }
    @Test void surfaceSelectionRejectsHighWallsDeepFallsAndInsufficientHeadroom(){
        assertNull(PartyTerrain.surface(start,List.of(66.0),p->true));
        assertNull(PartyTerrain.surface(start,List.of(62.0),p->true));
        assertNull(PartyTerrain.surface(start,List.of(65.0),p->false));
        assertEquals(64.5,PartyTerrain.surface(start,List.of(64.5,65.0),p->true).y());
        assertEquals(63,PartyTerrain.surface(start,List.of(63.0),p->true).y());
    }
    @Test void noJumpSpamInAirForDistantStepsOrUnclimbableWalls(){
        assertFalse(OrbitMotion.shouldJump(start,new PartySlots.Target(3.6,64.1,0),true));
        assertFalse(OrbitMotion.shouldJump(start,new PartySlots.Target(3.6,66,0),true));
        assertFalse(OrbitMotion.shouldJump(start,new PartySlots.Target(6.0,65,0),true));
        assertFalse(OrbitMotion.shouldJump(start,new PartySlots.Target(3.6,65,0),false));
    }
    @Test void turningSpeedZeroLeavesTheRingStill(){
        var frame=OrbitMotion.advance(center,start,start,new Vector(),0,1,p->p);
        assertEquals(start,frame.target());assertEquals(new Vector(),frame.velocity());
    }
    @Test void initialStandingSlotsAlsoUseActualSlabOrCarpetSurface(){
        var slots=new PartySlots();UUID source=UUID.randomUUID(),mob=UUID.randomUUID();
        var slot=slots.allocateResolved(source,mob,center,start,0.6,1.8,
                p->PartyTerrain.surface(p,List.of(64.0625),q->true));
        assertNotNull(slot);assertEquals(64.0625,slot.y());
        assertNull(slots.allocateResolved(source,mob,center,start,0.6,1.8,p->null));
    }
}
