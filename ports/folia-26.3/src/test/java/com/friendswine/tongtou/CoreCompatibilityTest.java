package com.friendswine.tongtou;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreCompatibilityTest {
    public static class OldData {public int getLevel(){return 3;}}
    public static class NewData {public int level(){return 4;}}
    @Test void nativeLevelAccessSupportsOldAndNewMethodNames()throws Exception{
        assertEquals(3,Native26Bridge.findMethod(OldData.class,"level","getLevel").invoke(new OldData()));
        assertEquals(4,Native26Bridge.findMethod(NewData.class,"level","getLevel").invoke(new NewData()));
    }
    @Test void nativeClassLookupDoesNotRequireNewPackageToExist()throws Exception{
        assertEquals(String.class,Native26Bridge.findClass("com.friendswine.no_such_class","java.lang.String"));
    }
    @Test void sharedAbsolutePoseRestoresNeutralAndKeepsDoubleClockPrecision(){
        var id=new UUID(0,1);var at=new WorldPoint(id,0,64,0);var config=new AnimationSettings(1,50,50,0.5);
        var stopped=new DollSnapshot(id,id,at,DollMode.STOPPED,-1,-1,0,0);
        assertEquals(new DancePose(1,1,0),DancePose.sample(stopped,1e12,config));
        var playing=new DollSnapshot(id,id,at,DollMode.FULL,0,0,0,0);
        double tick=10000000.123;
        assertEquals(-360*(tick/20)/com.friendswine.JellyAnimation.LENGTH*0.5,DancePose.sample(playing,tick,config).yaw(),1e-6);
    }
}
