package io.github.goraidebjyoti.dgchat.core;
public final class RetryPolicy {
    public static final int MAX_ATTEMPTS=24;
    private RetryPolicy(){}
    public static boolean allowed(int attempts,long expires,long now){return attempts<MAX_ATTEMPTS&&expires>now;}
    public static long next(int attempts,long now){return now+Math.min(300000L,5000L*(1L<<Math.min(6,Math.max(0,attempts))));}
    public static boolean terminal(String state){return state.equals("DELIVERED")||state.equals("READ")||state.equals("EXPIRED")||state.equals("FAILED");}
}
