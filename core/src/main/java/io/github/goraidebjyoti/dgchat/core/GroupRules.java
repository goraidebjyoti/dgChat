package io.github.goraidebjyoti.dgchat.core;
import java.util.Set;
/** Group recipients accept messages only under their current owner-approved membership snapshot. */
public final class GroupRules {
    private GroupRules(){}
    public static boolean accepts(String sender,Set<String> members,boolean joined,boolean active,int current,int incoming){
        return joined&&active&&current==incoming&&members.contains(sender);
    }
}
