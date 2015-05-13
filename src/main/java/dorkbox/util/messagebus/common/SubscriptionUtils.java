package dorkbox.util.messagebus.common;

import java.lang.reflect.Array;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import dorkbox.util.messagebus.common.thread.ClassHolder;

public class SubscriptionUtils {

    private final Map<Class<?>, Class<?>> arrayVersionCache;
    private final Map<Class<?>, Boolean> isArrayCache;

    private final ConcurrentMap<Class<?>, StrongConcurrentSetV8<Class<?>>> superClassesCache;
    private final ClassHolder classHolderSingle;


    public SubscriptionUtils(float loadFactor, int stripeSize) {
        this.arrayVersionCache = new ConcurrentHashMapV8<Class<?>, Class<?>>(64, loadFactor, stripeSize);
        this.isArrayCache = new ConcurrentHashMapV8<Class<?>, Boolean>(64, loadFactor, stripeSize);

        this.superClassesCache = new ConcurrentHashMapV8<Class<?>, StrongConcurrentSetV8<Class<?>>>(64, loadFactor, stripeSize);
        this.classHolderSingle = new ClassHolder(loadFactor, stripeSize);
    }

    /**
     * race conditions will result in duplicate answers, which we don't care if happens
     * never returns null
     * never reset, since it never needs to be reset (as the class hierarchy doesn't change at runtime)
     */
    public StrongConcurrentSetV8<Class<?>> getSuperClasses(Class<?> clazz) {
        return getSuperClasses(clazz, isArray(clazz));
    }

    public final StrongConcurrentSetV8<Class<?>> getSuperClasses(Class<?> clazz, boolean isArray) {
        // this is never reset, since it never needs to be.
        ConcurrentMap<Class<?>, StrongConcurrentSetV8<Class<?>>> local = this.superClassesCache;

        ClassHolder classHolderSingle = this.classHolderSingle;
        StrongConcurrentSetV8<Class<?>> classes = classHolderSingle.get();

        StrongConcurrentSetV8<Class<?>> putIfAbsent = local.putIfAbsent(clazz, classes);
        if (putIfAbsent == null) {
            // we are the first one in the map
            classHolderSingle.set(classHolderSingle.initialValue());

            // it doesn't matter if concurrent access stomps on values, since they are always the same.
            StrongConcurrentSetV8<Class<?>> superTypes = ReflectionUtils.getSuperTypes(clazz);

            ISetEntry<Class<?>> current = superTypes.head;
            Class<?> c;
            while (current != null) {
                c = current.getValue();
                current = current.next();

                if (isArray) {
                    c = getArrayClass(c);
                }

                if (c != clazz) {
                    classes.add(c);
                }
            }

            return classes;
        } else {
            // someone beat us
            return putIfAbsent;
        }
    }

    /**
     * race conditions will result in duplicate answers, which we don't care if happens
     * never returns null
     * never reset
     */
    public final Class<?> getArrayClass(Class<?> c) {
        Map<Class<?>, Class<?>> arrayVersionCache = this.arrayVersionCache;
        Class<?> clazz = arrayVersionCache.get(c);
        if (clazz == null) {
            // messy, but the ONLY way to do it. Array super types are also arrays
            Object[] newInstance = (Object[]) Array.newInstance(c, 1);
            clazz = newInstance.getClass();
            arrayVersionCache.put(c, clazz);
        }

        return clazz;
    }

    /**
     * Cache the values of JNI method, isArray(c)
     * @return true if the class c is an array type
     */
    @SuppressWarnings("boxing")
    public final boolean isArray(Class<?> c) {
        Map<Class<?>, Boolean> isArrayCache = this.isArrayCache;

        Boolean isArray = isArrayCache.get(c);
        if (isArray == null) {
            boolean b = c.isArray();
            isArrayCache.put(c, b);
            return b;
        }
        return isArray;
    }


    public void shutdown() {
        this.isArrayCache.clear();
        this.arrayVersionCache.clear();
        this.superClassesCache.clear();
    }

}
