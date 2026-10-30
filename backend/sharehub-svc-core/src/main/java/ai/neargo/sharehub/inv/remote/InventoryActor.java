package ai.neargo.sharehub.inv.remote;

import ai.neargo.sharehub.auth.SecurityUtils;
import java.util.function.Supplier;

/** Trusted execution attribution only. Does not replace authentication, permissions or data scope. */
public final class InventoryActor {
    private static final ThreadLocal<String> OVERRIDE=new ThreadLocal<>();
    private InventoryActor() {}
    public static String current(){
        String explicit=OVERRIDE.get();if(explicit!=null) return explicit;
        String current=SecurityUtils.userNo();return current==null?"SYSTEM":"STAFF:"+current;
    }
    public static <T> T asStaff(String no,Supplier<T> action){
        if(no==null||no.isBlank()) throw new IllegalArgumentException("Inventory executor required");
        String before=OVERRIDE.get();OVERRIDE.set("STAFF:"+no);
        try{return action.get();}finally{if(before==null)OVERRIDE.remove();else OVERRIDE.set(before);}
    }
}
