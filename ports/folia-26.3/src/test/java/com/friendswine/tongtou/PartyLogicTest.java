package com.friendswine.tongtou;

import com.friendswine.JellyAnimation;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kr.toxicity.model.api.bone.BoneMovement;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PartyLogicTest {
    private static final UUID WORLD=UUID.randomUUID();
    private static WorldPoint point(double x,double y,double z){return new WorldPoint(WORLD,x,y,z);}
    private static DollSnapshot doll(UUID id,double x,DollMode mode,long start,long rotation){
        return new DollSnapshot(id,id,point(x,64,0),mode,start,rotation,0,WorldPoint.packed((int)x,64,0));
    }
    @Test void fourModesKeepMusicAndSquashClockUntilStopped(){
        var state=new DollPlayback();assertEquals(DollMode.STOPPED,state.mode());
        state.cycle(10);assertEquals(DollMode.SQUASH,state.mode());assertEquals(10,state.startTick());
        state.cycle(30);assertEquals(DollMode.FULL,state.mode());assertEquals(10,state.startTick());assertEquals(30,state.rotationStartTick());
        state.cycle(50);assertEquals(DollMode.ORBIT,state.mode());assertEquals(10,state.startTick());assertEquals(30,state.rotationStartTick());
        state.cycle(70);assertEquals(DollMode.STOPPED,state.mode());assertEquals(0,state.elapsed(70));
        state.cycle(90);assertEquals(90,state.startTick());assertEquals(-1,state.rotationStartTick());
    }
    @Test void ordinaryClickStartsFullAndStopNeverRestartsItself(){
        var state=new DollPlayback();state.toggle(20);assertEquals(DollMode.FULL,state.mode());
        state.stop(30);for(int i=0;i<1000;i++)assertEquals(DollMode.STOPPED,state.mode());
        state.toggle(100);assertEquals(100,state.startTick());assertEquals(100,state.rotationStartTick());
    }
    @Test void loadAdvancesOnlyOnlineLogicalTicksAndHandlesInvalidMode(){
        var restored=DollPlayback.restore(2,200,100,1000,1500);
        assertEquals(700,restored.elapsed(1500));assertEquals(600,restored.rotated(1500));
        assertEquals(200,DollPlayback.restore(2,200,100,1000,1000).elapsed(1000));
        assertEquals(DollMode.STOPPED,DollPlayback.restore(999,0,0,0,1).mode());
    }
    @Test void nearestUsesThreeDimensionalBoundaryWorldAndStableTieBreak(){
        var index=new PartyIndex();UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        index.publish(doll(a,-10,DollMode.FULL,0,0));index.publish(doll(b,10,DollMode.FULL,0,0));
        assertNotNull(index.nearest(point(0,64,0),10));assertNull(index.nearest(point(0,75,0),10));
        assertNull(index.nearest(new WorldPoint(UUID.randomUUID(),0,64,0),50));
        var expected=index.nearest(point(0,64,0),50).id();
        for(int i=0;i<100;i++){index.publish(doll(a,-10,DollMode.FULL,0,0));assertEquals(expected,index.nearest(point(0,64,0),50).id());}
        index.publish(doll(expected,expected.equals(a)?-10:10,DollMode.STOPPED,-1,-1));
        assertNotEquals(expected,index.nearest(point(0,64,0),50).id());
        assertNotNull(index.get(expected),"停止的已加载玩偶仍可遥控");
    }
    @Test void overlapRemovalSwitchesSourceAndStoppedSourcesAreInactive(){
        var index=new PartyIndex();UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        index.publish(doll(a,0,DollMode.SQUASH,0,-1));index.publish(doll(b,2,DollMode.FULL,5,5));
        assertEquals(a,index.nearest(point(0,64,0),50).id());index.remove(a);
        assertEquals(b,index.nearest(point(0,64,0),50).id());
        index.publish(doll(b,2,DollMode.STOPPED,-1,-1));assertFalse(index.hasActive());assertNull(index.nearest(point(0,64,0),50));
        index.clear();assertEquals(0,index.size());
    }
    @Test void movingAcrossSpatialBucketsLeavesNoGhost(){
        var index=new PartyIndex();UUID id=UUID.randomUUID();index.publish(doll(id,-33,DollMode.FULL,0,0));
        assertEquals(1,index.nearby(point(-33,64,0),2).size());index.publish(doll(id,90,DollMode.FULL,0,0));
        assertTrue(index.nearby(point(-33,64,0),2).isEmpty());assertEquals(1,index.nearby(point(90,64,0),2).size());
        index.remove(id);assertTrue(index.nearby(point(90,64,0),2).isEmpty());
    }
    @Test void concurrentPublicationAndQueriesDoNotExposeMutableEntities()throws Exception{
        var index=new PartyIndex();List<Future<?>> work=new ArrayList<>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            for(int i=0;i<20;i++){UUID id=UUID.randomUUID();work.add(pool.submit(()->{
                for(int n=0;n<100;n++){index.publish(doll(id,n,DollMode.FULL,0,0));index.nearby(point(0,64,0),50);}index.remove(id);
            }));}
            for(var task:work)task.get();
        }
        assertEquals(0,index.size());assertFalse(index.hasActive());
    }
    @Test void slotsKeepClearanceAndReleasedSlotIsReusable(){
        var slots=new PartySlots();UUID source=UUID.randomUUID();List<PartySlots.Target> targets=new ArrayList<>();
        for(int i=0;i<8;i++){
            var target=slots.allocate(source,UUID.randomUUID(),point(0,64,0),new PartySlots.Target(0,64,0),0.6,1.8,p->true);
            assertNotNull(target);assertTrue(target.x()*target.x()+target.z()*target.z()<=Math.pow(5-0.3,2));
            for(var other:targets)assertTrue(Math.abs(target.x()-other.x())>=1.21||Math.abs(target.z()-other.z())>=1.21);targets.add(target);
        }
        slots.prune(Set.of());UUID mob=UUID.randomUUID();var first=slots.allocate(source,mob,point(0,64,0),new PartySlots.Target(0,64,0),0.6,1.8,p->true);
        slots.release(source,mob);assertEquals(first,slots.allocate(source,mob,point(0,64,0),first,0.6,1.8,p->true));
    }
    @Test void unsafeOrOversizedMobsDoNotGetForcedSlots(){
        var slots=new PartySlots();UUID source=UUID.randomUUID();var at=new PartySlots.Target(0,64,0);
        assertNull(slots.allocate(source,UUID.randomUUID(),point(0,64,0),at,20,20,p->true));
        assertNull(slots.allocate(source,UUID.randomUUID(),point(0,64,0),at,0.6,1.8,p->false));
    }
    @Test void orbitHasTwentySecondPeriodAndKeepsHeightAndRadius(){
        var center=point(5,64,5);var start=new PartySlots.Target(8,65,5);var at=start;
        for(int i=0;i<400;i++)at=at.orbit(center,2*Math.PI/400);
        assertEquals(start.x(),at.x(),1e-8);assertEquals(start.z(),at.z(),1e-8);assertEquals(65,at.y());
    }
    @Test void orbitMotionAdvancesTangentAtTheOriginalTwentySecondRate(){
        var center=point(0,64,0);var target=new PartySlots.Target(3.5,64,0);
        var frame=OrbitMotion.advance(center,target,target,new org.bukkit.util.Vector(),1,1,p->p);
        assertFalse(frame.needsPath());assertTrue(frame.advancing());
        assertTrue(frame.target().z()<0,"counterclockwise viewed from above");
        assertEquals(3.5,Math.hypot(frame.target().x(),frame.target().z()),1e-10);
        assertTrue(frame.velocity().getZ()<0,"tangential velocity must accompany the moving target");
        var whole=target;for(int i=0;i<400;i++)whole=whole.orbit(center,2*Math.PI/400);
        assertEquals(target.x(),whole.x(),1e-8);assertEquals(target.z(),whole.z(),1e-8);
    }
    @Test void blockedOrbitRequestsPathToAFreePointAheadInsteadOfStoppingAtObstacle(){
        var center=point(0,64,0);var target=new PartySlots.Target(3.5,64,0);
        var frame=OrbitMotion.advance(center,target,target,new org.bukkit.util.Vector(),1,1,p->p.z()<-0.12?p:null);
        assertTrue(frame.needsPath());assertTrue(frame.advancing());
        assertTrue(frame.target().z()<-0.12,"choose a reachable arc beyond the blocked step");
        assertEquals(64,frame.target().y());
        assertEquals(0,frame.velocity().getX());assertEquals(0,frame.velocity().getZ());
    }
    @Test void orbitAnchorPreservesRingRadiusAndRealGroundHeight(){
        var center=point(0,64.6,0);var current=new PartySlots.Target(3,64.6,0);
        var anchor=OrbitMotion.anchor(center,current,0.6,p->true);
        assertNotNull(anchor);assertEquals(64.6,anchor.y());assertEquals(3,Math.hypot(anchor.x(),anchor.z()),1e-10);
        assertNull(OrbitMotion.anchor(center,current,10,p->true));
        assertNull(OrbitMotion.anchor(center,current,0.6,p->false));
    }
    @Test void surfaceFinderCanLiftRouteWaypointOntoOneBlockAndKeepsSafeNearestFloor(){
        var at=new PartySlots.Target(1,64,1);
        var step=PartyTerrain.surface(at,List.of(63.0,64.0,65.0),p->p.y()>=64.99);
        assertEquals(new PartySlots.Target(1,65,1),step,"one-block obstacles use native navigation instead of clipping");
        var nearest=PartyTerrain.surface(new PartySlots.Target(1,64.6,1),List.of(64.0,65.0,65.5),p->true);
        assertEquals(65.0,nearest.y(),1e-10);
        assertNull(PartyTerrain.surface(at,List.of(64.0),p->false));
    }
    @Test void continuousRotationDoesNotJumpAtSquashOrSongLoopWithFractionalSpeed(){
        var source=new AtomicReference<>(doll(UUID.randomUUID(),0,DollMode.FULL,0,0));double[] time={0};
        var progress=new ContinuousDance.Progress(source::get,()->time[0],()->new AnimationSettings(1,50,50,0.5),new Vector3f(),true);
        var base=new BoneMovement();
        for(double boundary:new double[]{JellyAnimation.LENGTH,JellyAnimation.MUSIC_LENGTH,10000*JellyAnimation.LENGTH}){
            time[0]=(boundary-1e-5)*20;var before=progress.animate(base,new BoneMovement());
            time[0]=(boundary+1e-5)*20;var after=progress.animate(base,new BoneMovement());
            assertTrue(Math.abs(before.rotation().dot(after.rotation()))>0.99999);assertEquals(before.scale().y,after.scale().y,0.001);
        }
    }
    @Test void sameSamplerTracksModeAndSourceChangesAndStopReturnsBase(){
        UUID id=UUID.randomUUID();var source=new AtomicReference<>(doll(id,0,DollMode.SQUASH,0,-1));
        var progress=new ContinuousDance.Progress(source::get,()->5,()->new AnimationSettings(1,50,50,1),new Vector3f(0,1,0),true);
        var base=new BoneMovement();var squash=progress.animate(base,new BoneMovement());
        assertEquals(0.5,squash.scale().y,1e-5);assertEquals(-0.5,squash.position().y,1e-5);
        source.set(doll(id,0,DollMode.FULL,0,0));var full=progress.animate(base,new BoneMovement());
        assertEquals(squash.scale(),full.scale());assertNotEquals(squash.rotation(),full.rotation());
        source.set(doll(UUID.randomUUID(),0,DollMode.STOPPED,-1,-1));assertEquals(base,progress.animate(base,new BoneMovement()));
    }
    @Test void dailyStockDoesNotRefillForRepeatedClicksOrClockRewind(){
        assertFalse(RestockPolicy.refill(4,4));assertTrue(RestockPolicy.refill(4,5));assertFalse(RestockPolicy.refill(5,2));assertTrue(RestockPolicy.refill(2,3));
    }
    @Test void peaceProtectsEitherSideButNotOtherDimensionsOrStoppedSources(){
        var index=new PartyIndex();UUID id=UUID.randomUUID();index.publish(doll(id,0,DollMode.FULL,0,0));
        assertTrue(PeaceService.protectedPositions(index,point(50,64,0),point(80,64,0)));
        assertTrue(PeaceService.protectedPositions(index,point(80,64,0),point(50,64,0)));
        assertFalse(PeaceService.protectedPositions(index,point(50.001,64,0),point(80,64,0)));
        assertTrue(PeaceService.protectedPositions(index,null,point(0,64,0)));
        assertFalse(PeaceService.protectedPositions(index,point(0,64,0),new WorldPoint(UUID.randomUUID(),0,64,0)));
        index.publish(doll(id,0,DollMode.STOPPED,-1,-1));assertFalse(PeaceService.protectedPositions(index,point(0,64,0),point(0,64,0)));
    }
}
