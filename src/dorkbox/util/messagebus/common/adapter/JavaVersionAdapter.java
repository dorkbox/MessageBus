package dorkbox.util.messagebus.common.adapter;

import java.util.concurrent.ConcurrentMap;



public
class JavaVersionAdapter {


    private static final MapAdapter get;

    static {
        MapAdapter adapter;
        try {
            Class.forName("java.util.concurrent.locks.StampedLock");
            adapter = new Java8Adapter();
        } catch (Exception e) {
            adapter = new Java6Adapter();
        }

        get = adapter;
    }

    public static
    <K, V> ConcurrentMap<K, V> concurrentMap(final int size, final float loadFactor, final int stripeSize) {
        return get.concurrentMap(size, loadFactor, stripeSize);
    }
}
