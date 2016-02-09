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
package dorkbox.messagebus.common;

import com.esotericsoftware.kryo.util.IdentityMap;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simple tree structure that is a map that contains a chain of keys to publish to a value.
 *
 *
 * This Tree store "message classes"  as the key, and a unique object as the "value". This map is NEVER cleared (shutdown clears it), and
 * the "value" object is used to store/lookup in another map
 *
 * This data structure is used to keep track of multi-messages - where there is more that one parameter for publish().
 *
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class ClassTree<KEY> {
    public static int INITIAL_SIZE = 4;
    public static float LOAD_FACTOR = 0.8F;

    private static
    final ThreadLocal<IdentityMap> keyCache = new ThreadLocal<IdentityMap>() {
        @Override
        protected
        IdentityMap initialValue() {
            return new IdentityMap(INITIAL_SIZE, LOAD_FACTOR);
        }
    };

    private static
    final ThreadLocal<MultiClass> valueCache = new ThreadLocal<MultiClass>();

    private AtomicReference<Object> children = new AtomicReference<Object>();
    private AtomicReference<MultiClass> value = new AtomicReference<MultiClass>();
    private AtomicInteger valueId = new AtomicInteger(Integer.MIN_VALUE);


    @SuppressWarnings("unchecked")
    private static
    <KEY> IdentityMap<KEY, ClassTree<KEY>> cast(Object o) {
        return (IdentityMap<KEY, ClassTree<KEY>>) o;
    }


    public
    ClassTree() {
    }


    public final void clear() {
        // gc handles the rest
        this.children.set(null);
    }


    public final
    MultiClass get(KEY key) {
        if (key == null) {
            throw new NullPointerException("keys");
        }

        ClassTree<KEY> leaf = getOrCreateLeaf(key);
        return getOrCreateValue(leaf);
    }

    public final
    MultiClass get(KEY key1, KEY key2) {
        if (key1 == null || key2 == null) {
            throw new NullPointerException("keys");
        }

        // have to put value into our children
        ClassTree<KEY> leaf = getOrCreateLeaf(key1);
        leaf = leaf.getOrCreateLeaf(key2);

        return getOrCreateValue(leaf);
    }

    public final
    MultiClass get(KEY key1, KEY key2, KEY key3) {
        if (key1 == null || key2 == null || key3 == null) {
            throw new NullPointerException("keys");
        }

        // have to put value into our children
        ClassTree<KEY> leaf = getOrCreateLeaf(key1);
        leaf = leaf.getOrCreateLeaf(key2);
        leaf = leaf.getOrCreateLeaf(key3);

        return getOrCreateValue(leaf);
    }

    /**
     * creates a child (if necessary) in an atomic way. The tree returned will either be the current one, or a new one.
     *
     * @param key the key for the new child
     * @return the existing (or new) leaf
     */
    @SuppressWarnings("unchecked")
    private
    ClassTree<KEY> getOrCreateLeaf(KEY key) {
        if (key == null) {
            return null;
        }

        // create the children, then insert a tree @ KEY location inside children

        final Object cached = keyCache.get();
        final Object checked = children.get();
        IdentityMap<KEY, ClassTree<KEY>> kids;

        // create the children, if necessary
        if (checked == null) {
            final boolean success = children.compareAndSet(null, cached);
            if (success) {
                keyCache.set(new IdentityMap(INITIAL_SIZE, LOAD_FACTOR));
                kids = cast(cached);
            }
            else {
                kids = cast(children.get());
            }
        }
        else {
            kids = cast(checked);
        }

        // create a new tree inside the children, if necessary
        ClassTree<KEY> targetTree = kids.get(key);
        if (targetTree == null) {
            synchronized (this) {
                // only one thread can insert into the kids. DCL is safe because we safely publish at the end
                targetTree = kids.get(key);

                if (targetTree == null) {
                    targetTree = new ClassTree<KEY>();
                    kids.put(key, targetTree);
                }
            }
        }

        final boolean success = children.compareAndSet(kids, kids); // make sure our kids are what we expect and ALSO publishes them
        if (!success) {
            throw new RuntimeException("Error setting children in leaf!");
        }

        return targetTree;
    }


    /**
     * Gets, or creates, in an atomic way, the value for a MapTree
     *
     * @param leaf where to get/create the value
     * @return non-null
     */
    private
    MultiClass getOrCreateValue(final ClassTree<KEY> leaf) {
        MultiClass value = leaf.value.get();
        if (value == null) {
            MultiClass multiClass = valueCache.get();
            if (multiClass == null) {
                multiClass = new MultiClass(valueId.getAndIncrement());
            }

            final boolean success = leaf.value.compareAndSet(null, multiClass);
            if (success) {
                valueCache.set(new MultiClass(valueId.getAndIncrement()));
                return multiClass;
            } else {
                valueCache.set(multiClass);
                return leaf.value.get();
            }
        }
        return value;
    }
}
