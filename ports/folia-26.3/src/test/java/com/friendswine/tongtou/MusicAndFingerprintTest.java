package com.friendswine.tongtou;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MusicAndFingerprintTest {
    @TempDir Path temporary;
    @Test void musicLoopsAtExactOriginalSampleLengthAndSeeksWithinPagePrecision()throws Exception{
        var timeline=MusicTimeline.load(BuiltinAssets.bundled().open("music.json"));
        assertEquals(4,timeline.slots());assertEquals(2482560.0/44100,timeline.duration());
        assertEquals(new MusicTimeline.Cue(0,0),timeline.cue(0));
        assertEquals(0,timeline.cue(timeline.duration()-1e-5).loop());
        assertEquals(new MusicTimeline.Cue(1,0),timeline.cue(timeline.duration()+1e-5));
        assertTrue(timeline.cue(20).offset()>10);assertTrue(timeline.cue(20).offset()<22);
        assertEquals(500,timeline.cue(timeline.duration()*500+0.1).loop());
        assertEquals(timeline.cue(0),timeline.cue(-1));
    }
    @Test void ceOnlyAndAudioOnlyChangesInvalidateCombinedFingerprint()throws Exception{
        Path file=temporary.resolve("sounds.json");Files.writeString(file,"first");
        String before=ResourceFingerprint.combine("model-hash",temporary);
        assertEquals(before,ResourceFingerprint.combine("model-hash",temporary));
        Files.writeString(file,"other");assertNotEquals(before,ResourceFingerprint.combine("model-hash",temporary));
        assertNotEquals(ResourceFingerprint.combine("model-hash",temporary),ResourceFingerprint.combine("new-model",temporary));
    }
    @Test void fingerprintIncludesFileNamesAndMissingRootFails()throws Exception{
        Files.writeString(temporary.resolve("one"),"x");String before=ResourceFingerprint.combine("bm",temporary);
        Files.move(temporary.resolve("one"),temporary.resolve("two"));assertNotEquals(before,ResourceFingerprint.combine("bm",temporary));
        assertThrows(java.io.IOException.class,()->ResourceFingerprint.combine("bm",temporary.resolve("absent")));
    }
    @Test void partyConfigRejectsInvalidBudgetsAndNonFiniteOrbit(){
        assertThrows(IllegalArgumentException.class,()->new PartySettings(true,128,8192,256,Double.NaN,true,4,true));
        assertThrows(IllegalArgumentException.class,()->new PartySettings(true,128,8192,256,1,true,5,true));
        assertThrows(IllegalArgumentException.class,()->new PartySettings(true,0,8192,256,1,true,4,true));
        assertEquals(4,new PartySettings(true,128,8192,256,1,true,4,true).soundSources());
    }
}
