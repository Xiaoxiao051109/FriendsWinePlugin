package com.friendswine.tongtou;

import com.friendswine.JellyAnimation;

/** 两代模型后端共享绝对时钟的姿态计算，不在回弹或音乐接缝重置旋转。 */
public record DancePose(float x,float y,double yaw) {
    public static DancePose sample(DollSnapshot doll,double tick,AnimationSettings settings){
        if(doll==null||!doll.mode().playing())return new DancePose(1,1,0);
        float squash=JellyAnimation.musicSquash(doll.seconds(tick));
        return new DancePose(JellyAnimation.widthScale(squash,settings.width()),JellyAnimation.heightScale(squash,settings.compression()),
                doll.mode().rotating()?(-360*doll.rotationSeconds(tick)/JellyAnimation.LENGTH*settings.rotationSpeed()):0);
    }
}
