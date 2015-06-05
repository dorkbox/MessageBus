package dorkbox.util.messagebus.common;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Map;

public class SuperClassUtils {

    private final Map<Class<?>, Class<?>> versionCache;
    private final Map<Class<?>, Class<?>[]> superClassesCache;

    public SuperClassUtils(float loadFactor, int stripeSize) {
        this.versionCache = new ConcurrentHashMapV8<Class<?>, Class<?>>(32, loadFactor, stripeSize);
        this.superClassesCache = new ConcurrentHashMapV8<Class<?>, Class<?>[]>(32, loadFactor, stripeSize);
    }

    /**
     * never returns null
     * never reset, since it never needs to be reset (as the class hierarchy doesn't change at runtime)
     * <p>
     * if parameter clazz is of type array, then the super classes are of array type as well
     * <p>
     * protected by read lock by caller. The cache version is called first, by write lock
     */
    public final Class<?>[] getSuperClasses(final Class<?> clazz, final boolean isArray) {
        // this is never reset, since it never needs to be.
        final Map<Class<?>, Class<?>[]> local = this.superClassesCache;

        Class<?>[] classes = local.get(clazz);

        if (classes == null) {
            // publish all super types of class
            final Class<?>[] superTypes = ReflectionUtils.getSuperTypes(clazz);
            final int length = superTypes.length;

            ArrayList<Class<?>> newList = new ArrayList<Class<?>>(length);

            Class<?> c;
            if (isArray) {
                for (int i = 0; i < length; i++) {
                    c = superTypes[i];

                    c = getArrayClass(c);

                    if (c != clazz) {
                        newList.add(c);
                    }
                }
            }
            else {
                for (int i = 0; i < length; i++) {
                    c = superTypes[i];

                    if (c != clazz) {
                        newList.add(c);
                    }
                }
            }

            classes = new Class<?>[newList.size()];
            newList.toArray(classes);
            local.put(clazz, classes);
        }

        return classes;
    }

    /**
     * race conditions will result in duplicate answers, which we don't care if happens
     * never returns null
     * never reset
     */
    public final Class<?> getArrayClass(final Class<?> c) {
        final Map<Class<?>, Class<?>> versionCache = this.versionCache;
        Class<?> clazz = versionCache.get(c);

        if (clazz == null) {
            // messy, but the ONLY way to do it. Array super types are also arrays
            final Object[] newInstance = (Object[]) Array.newInstance(c, 1);
            clazz = newInstance.getClass();
            versionCache.put(c, clazz);
        }

        return clazz;
    }

    /**
     * Clears the caches on shutdown
     */
    public final void shutdown() {
        this.versionCache.clear();
        this.superClassesCache.clear();
    }
}
