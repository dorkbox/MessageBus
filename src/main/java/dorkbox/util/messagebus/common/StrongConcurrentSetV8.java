package dorkbox.util.messagebus.common;

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
        super(new ConcurrentHashMapV8<T, ISetEntry<T>>(size, loadFactor, 1));
    }
}