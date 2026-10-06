package io.github.goraidebjyoti.dgchat.core;
import java.util.*;
/** Keep alternatives across transports so a lost short BLE route doesn't hide a Wi-Fi/relay route. */
public final class Topology {
    public static final class Route {
        public final String destination,nextHop,transport; public final int hops; public final long expires;
        Route(String d,String n,String t,int h,long e){destination=d;nextHop=n;transport=t;hops=h;expires=e;}
    }
    private final Map<String,Route> routes=new HashMap<>();
    private static String key(String source,String via,String transport){return source+"|"+transport+"|"+via;}
    public synchronized void clear(){routes.clear();}
    public synchronized void observe(String source,String via,String transport,int hops,long now){
        expire(now);if(hops<1||hops>Packet.MAX_TTL)return;
        routes.put(key(source,via,transport),new Route(source,via,transport,hops,now+90000));
        if(routes.size()>2048)routes.remove(routes.entrySet().stream().min(Comparator.comparingLong(e->e.getValue().expires)).orElseThrow().getKey());
    }
    public synchronized List<Route> candidates(String destination,long now){
        expire(now);List<Route> result=new ArrayList<>();
        for(Route r:routes.values())if(r.destination.equals(destination))result.add(r);
        result.sort(Comparator.comparingInt((Route r)->r.transport.equals("Internet")?1:0).thenComparingInt(r->r.hops).thenComparing(r->r.transport).thenComparing(r->r.nextHop));
        return result;
    }
    public synchronized Route get(String destination,long now){List<Route> found=candidates(destination,now);return found.isEmpty()?null:found.get(0);}
    public synchronized void invalidate(String hop){routes.entrySet().removeIf(e->e.getValue().nextHop.equals(hop));}
    public synchronized void invalidate(String hop,String transport){routes.entrySet().removeIf(e->e.getValue().nextHop.equals(hop)&&e.getValue().transport.equals(transport));}
    public synchronized void invalidateTransport(String transport){routes.entrySet().removeIf(e->e.getValue().transport.equals(transport));}
    public synchronized void expire(long now){routes.entrySet().removeIf(e->e.getValue().expires<=now);}
    public synchronized List<Route> snapshot(long now){expire(now);return new ArrayList<>(routes.values());}
}
