package io.github.goraidebjyoti.dgchat.core;

/** Invalidates asynchronous work and rejects history predating a local clear. */
public final class HistoryGuard {
    private long generation, cutoff;
    private boolean blocked;
    public HistoryGuard(long cutoff, boolean pending) { this.cutoff=cutoff; blocked=pending; }
    public synchronized long generation() { return generation; }
    public synchronized long cutoff() { return cutoff; }
    public synchronized boolean blocked() { return blocked; }
    public synchronized void begin(long now) { blocked=true; generation++; cutoff=Math.max(cutoff,now); }
    public synchronized void complete() { blocked=false; }
    public synchronized boolean current(long token) { return !blocked && token==generation; }
    public synchronized boolean accepts(long created) { return !blocked && created>cutoff; }
}
