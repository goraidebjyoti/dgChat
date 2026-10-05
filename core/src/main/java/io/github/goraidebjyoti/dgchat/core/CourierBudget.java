package io.github.goraidebjyoti.dgchat.core;
import java.util.*;
/** Origin allocates at most three distinct custodians. Custodians deliver, never re-spray. */
public final class CourierBudget {
    private final Map<String,Set<String>> copies=new HashMap<>();
    public synchronized boolean reserve(String message,String peer){
        Set<String> s=copies.computeIfAbsent(message,k->new HashSet<>());
        if(s.size()>=3||s.contains(peer))return false;s.add(peer);return true;
    }
    public synchronized void forget(String message){copies.remove(message);}
}
