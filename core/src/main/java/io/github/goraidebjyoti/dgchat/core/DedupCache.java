package io.github.goraidebjyoti.dgchat.core;
import java.util.*;
public final class DedupCache {
    private final int capacity; private final long age;
    private final LinkedHashMap<String,Long> entries=new LinkedHashMap<>();
    public DedupCache(int capacity,long age){if(capacity<1||age<1)throw new IllegalArgumentException();this.capacity=capacity;this.age=age;}
    public synchronized boolean first(String id,long now){
        entries.entrySet().removeIf(e->now-e.getValue()>=age);
        if(entries.containsKey(id))return false;
        entries.put(id,now);while(entries.size()>capacity)entries.remove(entries.keySet().iterator().next());return true;
    }
    public synchronized int size(){return entries.size();}
}
