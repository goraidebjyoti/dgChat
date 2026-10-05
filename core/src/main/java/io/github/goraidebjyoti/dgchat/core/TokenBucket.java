package io.github.goraidebjyoti.dgchat.core;
public final class TokenBucket {
    private final double capacity,perMs;private double tokens;private long last;
    public TokenBucket(int capacity,int perSecond,long now){this.capacity=capacity;tokens=capacity;perMs=perSecond/1000.0;last=now;}
    public synchronized boolean take(long now){tokens=Math.min(capacity,tokens+Math.max(0,now-last)*perMs);last=now;if(tokens<1)return false;tokens--;return true;}
}
