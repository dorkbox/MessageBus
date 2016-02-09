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
package dorkbox.messagebus.utils;

import com.esotericsoftware.kryo.util.IdentityMap;
import dorkbox.messagebus.subscription.SubscriptionManager;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * @author dorkbox
 *         Date: 4/1/15
 */
public final
class ClassUtils {

    private volatile IdentityMap<Class<?>, Class<?>> arrayCache;
    private volatile IdentityMap<Class<?>, Class<?>[]> superClassesCache;

    // Recommended for best performance while adhering to the "single writer principle". Must be static-final
    private static final AtomicReferenceFieldUpdater<ClassUtils, IdentityMap> arrayREF =
                    AtomicReferenceFieldUpdater.newUpdater(ClassUtils.class,
                                                           IdentityMap.class,
                                                           "arrayCache");

    private static final AtomicReferenceFieldUpdater<ClassUtils, IdentityMap> superClassesREF =
                    AtomicReferenceFieldUpdater.newUpdater(ClassUtils.class,
                                                           IdentityMap.class,
                                                           "superClassesCache");

    /**
     * These data structures are never reset because the class hierarchy doesn't change at runtime. This class uses the "single writer
     * principle" for storing data, EVEN THOUGH it's not accessed by a single writer. This DOES NOT MATTER because duplicates DO NOT matter
     */
    public
    ClassUtils() {
        this.arrayCache = new IdentityMap<Class<?>, Class<?>>(32, SubscriptionManager.LOAD_FACTOR);
        this.superClassesCache = new IdentityMap<Class<?>, Class<?>[]>(32, SubscriptionManager.LOAD_FACTOR);
    }

    /**
     * if parameter clazz is of type array, then the super classes are of array type as well
     * <p>
     * race conditions will result in DUPLICATE answers, which we don't care if happens
     * never returns null
     * never reset (class hierarchy never changes during runtime)
     */
    public
    Class<?>[] getSuperClasses(final Class<?> clazz) {
        // access a snapshot of the subscriptions (single-writer-principle)
        final IdentityMap<Class<?>, Class<?>[]> cache = cast(superClassesREF.get(this));

        Class<?>[] classes = cache.get(clazz);

        // duplicates DO NOT MATTER
        if (classes == null) {
            // publish all super types of class
            final Iterator<Class<?>> superTypesIterator = ReflectionUtils.getSuperTypes(clazz);
            final ArrayList<Class<?>> newList = new ArrayList<Class<?>>(16);

            Class<?> c;
            final boolean isArray = clazz.isArray();

            if (isArray) {
                while (superTypesIterator.hasNext()) {
                    c = superTypesIterator.next();
                    c = getArrayClass(c);

                    if (c != clazz) {
                        newList.add(c);
                    }
                }
            }
            else {
                while (superTypesIterator.hasNext()) {
                    c = superTypesIterator.next();

                    if (c != clazz) {
                        newList.add(c);
                    }
                }
            }

            classes = new Class<?>[newList.size()];
            newList.toArray(classes);
            cache.put(clazz, classes);

            // save this snapshot back to the original (single writer principle)
            superClassesREF.lazySet(this, cache);
        }

        return classes;
    }

    /**
     * race conditions will result in DUPLICATE answers, which we don't care if happens
     * never returns null
     * never resets (class hierarchy never changes during runtime)
     *
     * https://bugs.openjdk.java.net/browse/JDK-6525802  (fixed this in 2007, so Array.newInstance is just as fast (via intrinsics) new [])
     * Cache is in place to keep GC down.
     */
    public
    Class<?> getArrayClass(final Class<?> c) {
        // access a snapshot of the subscriptions (single-writer-principle)
        final IdentityMap<Class<?>, Class<?>> cache = cast(arrayREF.get(this));

        Class<?> clazz = cache.get(c);

        if (clazz == null) {
            // messy, but the ONLY way to do it. Array super types are also arrays
            final Object[] newInstance = (Object[]) Array.newInstance(c, 0);
            clazz = newInstance.getClass();
            cache.put(c, clazz);

            // save this snapshot back to the original (single writer principle)
            arrayREF.lazySet(this, cache);
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

    @SuppressWarnings("unchecked")
    private static
    <T> T cast(Object obj) {
        return (T) obj;
    }
}
