package dorkbox.util.messagebus.common.adapter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;



public abstract class JavaVersionAdapter {



    static {
//        get = new Java7Adapter();
        get = new Java8Adapter();


    }

    public static JavaVersionAdapter get;

    public abstract <K, V> ConcurrentMap<K, V> concurrentMap(final int size, final float loadFactor, final int stripeSize);

    public <K, V> Map<K, V> hashMap(final int size, final float loadFactor) {
        return new ConcurrentHashMap<K, V>(size, loadFactor, 1);
    }


}
