package io.github.goraidebjyoti.dgchat.core;
import java.util.*;
public final class Topology {
    public static final class Route {
        public final String destination,nextHop,transport; public final int hops; public final long expires;
        Route(String d,String n,String t,int h,long e){destination=d;nextHop=n;transport=t;hops=h;expires=e;}
    }
    private final Map<String,Route> routes=new HashMap<>();
    public synchronized void clear(){routes.clear();}
    public synchronized void observe(String source,String via,String transport,int hops,long now){
        expire(now);if(hops<1||hops>7)return;
        Route old=routes.get(source);
        if(old==null||hops<=old.hops||old.nextHop.equals(via)) routes.put(source,new Route(source,via,transport,hops,now+90000));
        if(routes.size()>2048) routes.remove(routes.values().stream().min(Comparator.comparingLong(r->r.expires)).orElseThrow(()->new IllegalStateException("empty route table")).destination);
    }
    public synchronized Route get(String destination,long now){expire(now);return routes.get(destination);}
    public synchronized void invalidate(String hop){routes.entrySet().removeIf(e->e.getValue().nextHop.equals(hop));}
    public synchronized void expire(long now){routes.entrySet().removeIf(e->e.getValue().expires<=now);}
    public synchronized List<Route> snapshot(long now){expire(now);return new ArrayList<>(routes.values());}
}
