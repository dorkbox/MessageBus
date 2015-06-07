package dorkbox.util.messagebus.utils;

import dorkbox.util.messagebus.common.ConcurrentHashMapV8;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;

public final class ClassUtils {

    private final Map<Class<?>, Class<?>> arrayCache;
    private final Map<Class<?>, Class<?>[]> superClassesCache;

    public ClassUtils(final float loadFactor) {
        this.arrayCache = new ConcurrentHashMapV8<Class<?>, Class<?>>(32, loadFactor, 1);
        this.superClassesCache = new ConcurrentHashMapV8<Class<?>, Class<?>[]>(32, loadFactor, 1);
    }

    /**
     * never returns null
     * never reset, since it never needs to be reset (as the class hierarchy doesn't change at runtime)
     * <p>
     * if parameter clazz is of type array, then the super classes are of array type as well
     * <p>
     * protected by read lock by caller. The cache version is called first, by write lock
     */
    public Class<?>[] getSuperClasses(final Class<?> clazz) {
        // this is never reset, since it never needs to be.
        final Map<Class<?>, Class<?>[]> local = this.superClassesCache;

        Class<?>[] classes = local.get(clazz);

        if (classes == null) {
            // publish all super types of class
            final Class<?>[] superTypes = ReflectionUtils.getSuperTypes(clazz);
            final int length = superTypes.length;

            final ArrayList<Class<?>> newList = new ArrayList<Class<?>>(length);

            Class<?> c;
            final boolean isArray = clazz.isArray();

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
    public Class<?> getArrayClass(final Class<?> c) {
        final Map<Class<?>, Class<?>> arrayCache = this.arrayCache;
        Class<?> clazz = arrayCache.get(c);

        if (clazz == null) {
            // messy, but the ONLY way to do it. Array super types are also arrays
            final Object[] newInstance = (Object[]) Array.newInstance(c, 1);
            clazz = newInstance.getClass();
            arrayCache.put(c, clazz);
        }

        return clazz;
    }


    /**
     * Clears the caches
     */
    public void clear() {
        this.arrayCache.clear();
        this.superClassesCache.clear();
    }

    public static <T> ArrayList<T> findCommon(final T[] arrayOne, final T[] arrayTwo) {

        T[] arrayToHash;
        T[] arrayToSearch;

        final int size1 = arrayOne.length;
        final int size2 = arrayTwo.length;

        final int hashSize;
        final int searchSize;

        if (size1 < size2) {
            hashSize = size1;
            searchSize = size2;
            arrayToHash = arrayOne;
            arrayToSearch = arrayTwo;
        }
        else {
            hashSize = size2;
            searchSize = size1;
            arrayToHash = arrayTwo;
            arrayToSearch = arrayOne;
        }


        final ArrayList<T> intersection = new ArrayList<T>(searchSize);

        final HashSet<T> hashedArray = new HashSet<T>();
        for (int i = 0; i < hashSize; i++) {
            T t = arrayToHash[i];
            hashedArray.add(t);
        }

        for (int i = 0; i < searchSize; i++) {
            T t = arrayToSearch[i];
            if (hashedArray.contains(t)) {
                intersection.add(t);
            }
        }

        return intersection;
    }

    public static <T> ArrayList<T> findCommon(final ArrayList<T> arrayOne, final ArrayList<T> arrayTwo) {

        ArrayList<T> arrayToHash;
        ArrayList<T> arrayToSearch;

        final int size1 = arrayOne.size();
        final int size2 = arrayTwo.size();

        final int hashSize;
        final int searchSize;

        if (size1 < size2) {
            hashSize = size1;
            searchSize = size2;
            arrayToHash = arrayOne;
            arrayToSearch = arrayTwo;
        }
        else {
            hashSize = size2;
            searchSize = size1;
            arrayToHash = arrayTwo;
            arrayToSearch = arrayOne;
        }

        ArrayList<T> intersection = new ArrayList<T>(searchSize);

        HashSet<T> hashedArray = new HashSet<T>();
        for (int i = 0; i < hashSize; i++) {
            T t = arrayToHash.get(i);
            hashedArray.add(t);
        }

        for (int i = 0; i < searchSize; i++) {
            T t = arrayToSearch.get(i);
            if (hashedArray.contains(t)) {
                intersection.add(t);
            }
        }

        return intersection;
    }
}
