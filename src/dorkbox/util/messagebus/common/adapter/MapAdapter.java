package dorkbox.util.messagebus.common.adapter;

import java.util.concurrent.ConcurrentMap;

/**
 *
 */
public
interface MapAdapter {
    <K, V> ConcurrentMap<K, V> concurrentMap(final int size, final float loadFactor, final int stripeSize);
}
