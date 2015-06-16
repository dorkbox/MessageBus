package dorkbox.util.messagebus.common;

import dorkbox.util.messagebus.common.adapter.JavaVersionAdapter;

/**
 * This implementation uses strong references to the elements, uses an IdentityHashMap
 * <p/>
 *
 * @author dorkbox
 *         Date: 2/2/15
 */
public class StrongConcurrentSetV8<T> extends StrongConcurrentSet<T> {

    public StrongConcurrentSetV8(int size, float loadFactor) {
        // 1 for the stripe size, because that is the max concurrency with our concurrent set (since it uses R/W locks)
        super(JavaVersionAdapter.get.<T, ISetEntry<T>>concurrentMap(size, loadFactor, 16));
    }

    public StrongConcurrentSetV8(int size, float loadFactor, int stripeSize) {
        // 1 for the stripe size, because that is the max concurrency with our concurrent set (since it uses R/W locks)
        super(JavaVersionAdapter.get.<T, ISetEntry<T>>concurrentMap(size, loadFactor, stripeSize));
    }
}
