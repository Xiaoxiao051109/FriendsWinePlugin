package com.friendswine.tongtou;

import com.friendswine.JellyAnimation;

public record AnimationSettings(int cycles, double compression, double width, double rotationSpeed) {
    public AnimationSettings {
        if (cycles < 1 || cycles > 20 || !Double.isFinite(compression) || compression < 0 || compression > 90
                || !Double.isFinite(width) || width < 0 || width > 200
                || !Double.isFinite(rotationSpeed) || rotationSpeed < 0 || rotationSpeed > 4) {
            throw new IllegalArgumentException("动画参数超出范围");
        }
    }
    public double duration() { return cycles * JellyAnimation.LENGTH; }
    public int ticks() { return (int) Math.ceil(duration() * 20); }
    public Frame frame(double seconds) {
        if (!Double.isFinite(seconds)) throw new IllegalArgumentException("动画时间必须是有限数值");
        double t = Math.clamp(seconds, 0, duration());
        float squash = t >= duration() ? 0 : JellyAnimation.squash(t);
        return new Frame(JellyAnimation.widthScale(squash, width), JellyAnimation.heightScale(squash, compression),
                (float) (-360 * t / JellyAnimation.LENGTH * rotationSpeed));
    }
    public record Frame(float x, float y, float yaw) {}
}
