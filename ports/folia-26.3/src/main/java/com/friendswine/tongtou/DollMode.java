package com.friendswine.tongtou;

public enum DollMode {
    STOPPED, SQUASH, FULL, ORBIT;
    public boolean playing() { return this != STOPPED; }
    public boolean rotating() { return this == FULL || this == ORBIT; }
    public DollMode next() { return values()[(ordinal() + 1) % values().length]; }
    public static DollMode stored(int id) { return id >= 0 && id < values().length ? values()[id] : STOPPED; }
}
