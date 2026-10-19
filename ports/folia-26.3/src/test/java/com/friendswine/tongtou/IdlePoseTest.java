package com.friendswine.tongtou;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import kr.toxicity.model.api.animation.*;
import kr.toxicity.model.api.bone.BoneMovement;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdlePoseTest {
    private static DollSnapshot snapshot(DollMode mode){
        UUID id=UUID.fromString("00000000-0000-0000-0000-000000000001");
        return new DollSnapshot(id,id,new WorldPoint(id,0,64,0),mode,0,0,0,0);
    }
    private static final class Driver {
        final AtomicReference<DollSnapshot> source=new AtomicReference<>(snapshot(DollMode.FULL));
        final BoneMovement base=new BoneMovement(),shown=new BoneMovement();
        final AnimationStateHandler<AnimationProgress> engine;
        Driver(){
            base.position().set(0.25,1,0.0625);base.rotation().rotationY(0.4F);
            var frames=new AnimationProgress[]{
                    new ContinuousDance.Progress(source::get,()->5,()->new AnimationSettings(1,50,50,1),new Vector3f(0,0,0.0625F),false),
                    new ContinuousDance.Progress(source::get,()->5,()->new AnimationSettings(1,50,50,1),new Vector3f(0,0,0.0625F),false)};
            engine=new AnimationStateHandler<>(AnimationProgress.EMPTY,(before,after)->after.animate(base,shown));
            engine.addAnimation("doll-pose",AnimationIterator.Type.LOOP.create(new AnimationKeyframe(frames)),ContinuousDance.modifier(),()->{});
        }
        void tick(){for(int i=0;i<100;i++)engine.tick();}
    }
    @Test void pauseAtCompressionAndRotationRestoresDefaultPoseThroughRealAnimationEngine(){
        var driver=new Driver();driver.tick();
        assertEquals(1.5,driver.shown.scale().x,1e-5);assertEquals(0.5,driver.shown.scale().y,1e-5);
        assertNotEquals(driver.base.rotation(),driver.shown.rotation());
        driver.source.set(snapshot(DollMode.STOPPED));driver.tick();
        assertEquals(driver.base,driver.shown,"暂停后必须写回原缩放、旋转、位置，而不是保留停止前的最后一帧");
        assertNotNull(driver.engine.runningAnimation(),"同一个采样器在停止态继续输出默认姿态");
    }
    @Test void stoppedDollRemainsNeutralAndResumesWithoutRecreatingAnimation(){
        var driver=new Driver();driver.source.set(snapshot(DollMode.STOPPED));driver.tick();
        for(int i=0;i<5;i++){driver.tick();assertEquals(driver.base,driver.shown);}
        driver.source.set(snapshot(DollMode.ORBIT));driver.tick();assertEquals(0.5,driver.shown.scale().y,1e-5);
        driver.source.set(snapshot(DollMode.STOPPED));driver.tick();assertEquals(driver.base,driver.shown);
    }
    @Test void everyPlayingModeCanStopWithoutLeavingScaleOrYawBehind(){
        for(var mode:List.of(DollMode.SQUASH,DollMode.FULL,DollMode.ORBIT)){
            var driver=new Driver();driver.source.set(snapshot(mode));driver.tick();assertNotEquals(driver.base,driver.shown);
            driver.source.set(snapshot(DollMode.STOPPED));driver.tick();assertEquals(driver.base,driver.shown,mode.toString());
        }
    }
}
