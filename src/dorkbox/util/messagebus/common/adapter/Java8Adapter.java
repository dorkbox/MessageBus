package dorkbox.util.messagebus.common.adapter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public
class Java8Adapter implements MapAdapter {
    @Override
    public final
    <K, V> ConcurrentMap<K, V> concurrentMap(final int size, final float loadFactor, final int stripeSize) {
        return new ConcurrentHashMap<K, V>(size, loadFactor, stripeSize);
    }
}
