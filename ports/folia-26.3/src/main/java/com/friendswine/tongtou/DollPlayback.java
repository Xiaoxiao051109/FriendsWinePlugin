package com.friendswine.tongtou;

/** 纯状态机：档位不重置歌曲，旋转只在首次进入FULL时起算。 */
public final class DollPlayback {
    private DollMode mode;
    private long startTick, rotationStart;
    public DollPlayback() { mode = DollMode.STOPPED; startTick = rotationStart = -1; }
    public DollMode mode() { return mode; }
    public long startTick() { return startTick; }
    public long rotationStartTick() { return rotationStart; }
    public void toggle(long tick) { set(mode.playing() ? DollMode.STOPPED : DollMode.FULL, tick); }
    public void cycle(long tick) { set(mode.next(), tick); }
    public void stop(long tick) { set(DollMode.STOPPED, tick); }
    private void set(DollMode next, long tick) {
        if (tick < 0) throw new IllegalArgumentException("播放时钟不能为负数");
        if (!next.playing()) { startTick = rotationStart = -1; }
        else {
            if (!mode.playing()) startTick = tick;
            if (next.rotating() && !mode.rotating()) rotationStart = tick;
        }
        mode = next;
    }
    public static DollPlayback restore(int mode, long elapsed, long rotated, long savedClock, long now) {
        var playback = new DollPlayback();
        playback.mode = DollMode.stored(mode);
        if (playback.mode.playing()) {
            long delta = Math.max(0, now - Math.max(0, savedClock));
            playback.startTick = now - Math.max(0, elapsed) - delta;
            playback.rotationStart = playback.mode.rotating() ? now - Math.max(0, rotated) - delta : -1;
        }
        return playback;
    }
    public long elapsed(long now) { return mode.playing() ? Math.max(0, now - startTick) : 0; }
    public long rotated(long now) { return mode.rotating() ? Math.max(0, now - rotationStart) : 0; }
}
