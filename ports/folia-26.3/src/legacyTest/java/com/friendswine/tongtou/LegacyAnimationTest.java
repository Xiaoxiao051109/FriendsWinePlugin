package com.friendswine.tongtou;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import kr.toxicity.model.api.animation.*;
import kr.toxicity.model.api.bone.BoneMovement;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 单独源集；按用户选择，当前仅编译，未经明确授权不执行新下载的BM1代码。 */
class LegacyAnimationTest {
    private DollSnapshot snapshot(DollMode mode){var id=new UUID(0,1);return new DollSnapshot(id,id,new WorldPoint(id,0,64,0),mode,0,0,0,0);}
    private BoneMovement base(){return new BoneMovement(new Vector3f(),new Vector3f(1),new Quaternionf(),new Vector3f());}
    @Test void realOldIteratorSamplesLiveClockWithoutRestarting(){
        var source=new AtomicReference<>(snapshot(DollMode.FULL));double[] tick={5};
        var frames=LegacyAnimationFrames.dynamic(source::get,()->tick[0],()->new AnimationSettings(1,50,50,0.5),new Vector3f(),true);
        var iterator=AnimationIterator.Type.LOOP.create(frames);var first=iterator.next();
        assertEquals(0.5,base().plus(first,new BoneMovement()).scale().y,1e-5);
        tick[0]=100;var later=iterator.next();assertNotEquals(first.rotation(),later.rotation());
        source.set(snapshot(DollMode.STOPPED));var stopped=iterator.next();
        var restored=base().plus(stopped,new BoneMovement());assertEquals(base(),restored);
        source.set(snapshot(DollMode.ORBIT));tick[0]=5;assertEquals(0.5,base().plus(iterator.next(),new BoneMovement()).scale().y,1e-5);
    }
    @Test void oldStateMachineReturnsToNeutralAfterAllPlayingModes(){
        for(var mode:List.of(DollMode.SQUASH,DollMode.FULL,DollMode.ORBIT)){
            var source=new AtomicReference<>(snapshot(mode));var base=base();var shown=new BoneMovement();
            var frames=LegacyAnimationFrames.dynamic(source::get,()->5,()->new AnimationSettings(1,50,50,1),new Vector3f(),true);
            var engine=new AnimationStateHandler<>(AnimationMovement.EMPTY,(before,after)->base.plus(after,shown));
            engine.addAnimation("doll",AnimationIterator.Type.LOOP.create(frames),AnimationModifier.builder().type(AnimationIterator.Type.LOOP).override(false).build(),new AnimationEventHandler());
            for(int i=0;i<100;i++)engine.tick();assertEquals(0.5,shown.scale().y,1e-5);
            source.set(snapshot(DollMode.STOPPED));for(int i=0;i<100;i++)engine.tick();assertEquals(base,shown);
        }
    }
    @Test void fractionalRotationIsContinuousAcrossSquashAndMusicLoops(){
        var source=new AtomicReference<>(snapshot(DollMode.FULL));double[] tick={0};
        var frames=LegacyAnimationFrames.dynamic(source::get,()->tick[0],()->new AnimationSettings(1,50,50,0.5),new Vector3f(),true);
        for(double boundary:new double[]{com.friendswine.JellyAnimation.LENGTH,com.friendswine.JellyAnimation.MUSIC_LENGTH}){
            tick[0]=(boundary-0.00001)*20;var before=base().plus(frames.get(0),new BoneMovement());
            tick[0]=(boundary+0.00001)*20;var after=base().plus(frames.get(1),new BoneMovement());
            assertTrue(Math.abs(before.rotation().dot(after.rotation()))>0.99999);
        }
    }
}
