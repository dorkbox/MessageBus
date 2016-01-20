/*
 * Copyright 2015 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.util.messagebus.utils;

import com.esotericsoftware.kryo.util.IdentityMap;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashSet;

public final
class ClassUtils {

    private final IdentityMap<Class<?>, Class<?>> arrayCache;
    private final IdentityMap<Class<?>, Class<?>[]> superClassesCache;

    /**
     * These data structures are never reset because the class hierarchy doesn't change at runtime
     */
    public
    ClassUtils(final float loadFactor) {
        this.arrayCache = new IdentityMap<Class<?>, Class<?>>(32, loadFactor);
        this.superClassesCache = new IdentityMap<Class<?>, Class<?>[]>(32, loadFactor);
    }

    /**
     * if parameter clazz is of type array, then the super classes are of array type as well
     * <p>
     * race conditions will result in duplicate answers, which we don't care if happens
     * never returns null
     * never reset
     */
    public
    Class<?>[] getSuperClasses(final Class<?> clazz) {
        // this is never reset, since it never needs to be.
        final IdentityMap<Class<?>, Class<?>[]> cache = this.superClassesCache;

        Class<?>[] classes = cache.get(clazz);

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
            cache.put(clazz, classes);
        }

        return classes;
    }

    /**
     * race conditions will result in duplicate answers, which we don't care if happens
     * never returns null
     * never resets
     *
     * https://bugs.openjdk.java.net/browse/JDK-6525802  (fixed this in 2007, so Array.newInstance is just as fast (via intrinsics) new [])
     */
    public
    Class<?> getArrayClass(final Class<?> c) {
        // this is never reset, since it never needs to be.
        final IdentityMap<Class<?>, Class<?>> cache = this.arrayCache;
        Class<?> clazz = cache.get(c);

        if (clazz == null) {
            // messy, but the ONLY way to do it. Array super types are also arrays
            final Object[] newInstance = (Object[]) Array.newInstance(c, 0);
            clazz = newInstance.getClass();
            cache.put(c, clazz);
        }

        return clazz;
    }


    /**
     * Clears the caches, should only be called on shutdown
     */
    public
    void shutdown() {
        this.arrayCache.clear();
        this.superClassesCache.clear();
    }

    public static
    <T> ArrayList<T> findCommon(final T[] arrayOne, final T[] arrayTwo) {

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

    public static
    <T> ArrayList<T> findCommon(final ArrayList<T> arrayOne, final ArrayList<T> arrayTwo) {

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
