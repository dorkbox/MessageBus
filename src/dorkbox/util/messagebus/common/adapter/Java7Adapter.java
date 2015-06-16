package dorkbox.util.messagebus.common.adapter;

import java.util.concurrent.ConcurrentMap;

/**
 *
 */
public class Java7Adapter extends JavaVersionAdapter {

    @Override
    public final <K, V> ConcurrentMap<K, V> concurrentMap(final int size, final float loadFactor, final int stripeSize) {
        return new ConcurrentHashMapV8<K, V>(size, loadFactor, stripeSize);
    }
}
