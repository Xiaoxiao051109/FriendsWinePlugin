package com.friendswine.tongtou;

final class RestockPolicy {
    private RestockPolicy() {}
    static boolean refill(long previous,long day){return day>previous;}
}
