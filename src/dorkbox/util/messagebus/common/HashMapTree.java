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
package dorkbox.util.messagebus.common;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Simple tree structure that is a map that contains a chain of keys to publish to a value.
 *
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class HashMapTree<KEY, VALUE> {
    public static int INITIAL_SIZE = 4;
    public static float LOAD_FACTOR = 0.8F;


    private static
    final ThreadLocal<Object> keyCache = new ThreadLocal<Object>() {
        @Override
        protected
        Object initialValue() {
            return new ConcurrentHashMap(INITIAL_SIZE, LOAD_FACTOR, 1);
        }
    };

    private static
    final ThreadLocal<Object> valueCache = new ThreadLocal<Object>() {
        @Override
        protected
        Object initialValue() {
            return new HashMapTree();
        }
    };

//      Map<KEY, HashMapTree<KEY, VALUE>>
    private AtomicReference<Object> children = new AtomicReference<Object>();
    private VALUE value;



    @SuppressWarnings("unchecked")
    public static <KEY, VALUE> ConcurrentMap<KEY, HashMapTree<KEY, VALUE>> cast(Object o) {
        return (ConcurrentMap<KEY, HashMapTree<KEY, VALUE>>) o;
    }




    public HashMapTree() {
    }


    public final VALUE getValue() {
        return this.value;
    }


    public final void putValue(VALUE value) {
        this.value = value;
    }


    public final void removeValue() {
        this.value = null;
    }


    public final void clear() {
//        if (this.children != null) {
//            Set<Entry<KEY, HashMapTree<KEY, VALUE>>> entrySet = this.children.entrySet();
//            for (Entry<KEY, HashMapTree<KEY, VALUE>> entry : entrySet) {
//                entry.getValue().clear();
//            }
//
//            this.children.clear();
//            this.value = null;
//        }
    }


    public final VALUE put(KEY key, VALUE value) {
        if (key == null) {
            throw new NullPointerException("keys");
        }

        // have to put value into our children
        HashMapTree<KEY, VALUE> leaf = createLeaf(key);
        VALUE prev = leaf.value;
        leaf.value = value;

        return prev;
    }

    public final VALUE put(VALUE value, KEY key1, KEY key2) {
        if (key1 == null || key2 == null) {
            throw new NullPointerException("keys");
        }

        // have to put value into our children
        HashMapTree<KEY, VALUE> leaf = createLeaf(key1);
        leaf = leaf.createLeaf(key2);

        VALUE prev = leaf.value;
        leaf.value = value;

        return prev;
    }

    public final VALUE put(VALUE value, KEY key1, KEY key2, KEY key3) {
        if (key1 == null || key2 == null || key3 == null) {
            throw new NullPointerException("keys");
        }

        // have to put value into our children
        HashMapTree<KEY, VALUE> leaf = createLeaf(key1);
        leaf = leaf.createLeaf(key2);
        leaf = leaf.createLeaf(key3);

        VALUE prev = leaf.value;
        leaf.value = value;

        return prev;
    }

    public final VALUE put(VALUE value, KEY... keys) {
        if (keys == null) {
            throw new NullPointerException("keys");
        }

        int length = keys.length;

        // have to put value into our children
        HashMapTree<KEY, VALUE> leaf = createLeaf(keys[0]);
        for (int i=1;i<length;i++) {
            leaf = leaf.createLeaf(keys[i]);
        }

        VALUE prev = leaf.value;
        leaf.value = value;

        return prev;
    }

    public final HashMapTree<KEY, VALUE> createLeaf(KEY... keys) {
        if (keys == null) {
            return this;
        }

        int length = keys.length;

        // have to put value into our children
        HashMapTree<KEY, VALUE> leaf = createLeaf(keys[0]);
        for (int i=1;i<length;i++) {
            leaf = leaf.createLeaf(keys[i]);
        }

        return leaf;
    }



    /**
     * creates a child (if necessary) in an atomic way. The tree returned will either be the current one, or a new one.
     *
     * @param key the key for the new child
     * @return
     */
    @SuppressWarnings("unchecked")
    private
    HashMapTree<KEY, VALUE> createLeaf(KEY key) {
        if (key == null) {
            return null;
        }

        final Object cached = keyCache.get();
        final Object checked = children.get();
        ConcurrentMap<KEY, HashMapTree<KEY, VALUE>> kids;

        if (checked == null) {
            final boolean success = children.compareAndSet(null, cached);
            if (success) {
                keyCache.set(new ConcurrentHashMap(INITIAL_SIZE, LOAD_FACTOR, 1));
                kids = cast(cached);
            }
            else {
                kids = cast(children.get());
            }
        }
        else {
            kids = cast(checked);
        }


        final Object cached2 = valueCache.get();
        final HashMapTree<KEY, VALUE> tree = kids.putIfAbsent(key, (HashMapTree<KEY, VALUE>) cached2);
        if (tree == null) {
            // was absent
            valueCache.set(new HashMapTree());
            return (HashMapTree<KEY, VALUE>) cached2;
        }
        else {
            return tree;
        }
    }






    /////////////////////////////////////////
    /////////////////////////////////////////
    /////////////////////////////////////////
    /////////////////////////////////////////


    public final VALUE get(KEY key) {
        if (key == null) {
            return null;
        }

        HashMapTree<KEY, VALUE> objectTree;
        // publish value from our children
        objectTree = getLeaf(key);

        if (objectTree == null) {
            return null;
        }

        return objectTree.value;
    }

    public final VALUE get(KEY key1, KEY key2) {
        HashMapTree<KEY, VALUE> tree;
        // publish value from our children
        tree = getLeaf(key1); // protected by lock
        if (tree != null) {
            tree = tree.getLeaf(key2);
        }

        if (tree == null) {
            return null;
        }

        return tree.value;
    }

    public final VALUE get(KEY key1, KEY key2, KEY key3) {
        HashMapTree<KEY, VALUE> tree;
        // publish value from our children
        tree = getLeaf(key1);
        if (tree != null) {
            tree = tree.getLeaf(key2);
        }
        if (tree != null) {
            tree = tree.getLeaf(key3);
        }

        if (tree == null) {
            return null;
        }

        return tree.value;
    }

    @SuppressWarnings("unchecked")
    public final VALUE get(KEY... keys) {
        HashMapTree<KEY, VALUE> tree;

        // publish value from our children
        tree = getLeaf(keys[0]);

        int size = keys.length;
        for (int i=1;i<size;i++) {
            if (tree != null) {
                tree = tree.getLeaf(keys[i]);
            } else {
                return null;
            }
        }

        if (tree == null) {
            return null;
        }

        return tree.value;
    }

    public final HashMapTree<KEY, VALUE> getLeaf(KEY key) {
        if (key == null) {
            return null;
        }

        HashMapTree<KEY, VALUE> tree;

        if (this.children == null) {
            tree = null;
        } else {
            final ConcurrentMap<KEY, HashMapTree<KEY, VALUE>> o = cast(this.children.get());
            tree = o.get(key);
        }

        return tree;
    }


    public final HashMapTree<KEY, VALUE> getLeaf(KEY key1, KEY key2) {
        HashMapTree<KEY, VALUE> tree;

        // publish value from our children
        tree = getLeaf(key1);
        if (tree != null) {
            tree = tree.getLeaf(key2);
        }

        return tree;
    }

    public final HashMapTree<KEY, VALUE> getLeaf(KEY key1, KEY key2, KEY key3) {
        HashMapTree<KEY, VALUE> tree;

        // publish value from our children
        tree = getLeaf(key1);
        if (tree != null) {
            tree = tree.getLeaf(key2);
        }
        if (tree != null) {
            tree = tree.getLeaf(key3);
        }

        return tree;
    }

    @SuppressWarnings("unchecked")
    public final HashMapTree<KEY, VALUE> getLeaf(KEY... keys) {
        int size = keys.length;

        if (size == 0) {
            return null;
        }

        HashMapTree<KEY, VALUE> tree;
        // publish value from our children
        tree = getLeaf(keys[0]);

        for (int i=1;i<size;i++) {
            if (tree != null) {
                tree = tree.getLeaf(keys[i]);
            } else {
                return null;
            }
        }

        return tree;
    }



    /////////////////////////////////////////
    /////////////////////////////////////////
    /////////////////////////////////////////
    /////////////////////////////////////////



    /**
     * This <b>IS NOT</b> safe to call outside of root.remove(...)
     * <p>
     * Removes a branch from the tree, and cleans up, if necessary
     */
    public final void remove(KEY key) {
        if (key != null) {
            removeLeaf(key);
        }
    }


    /**
     * This <b>IS NOT</b> safe to call outside of root.remove(...)
     * <p>
     * Removes a branch from the tree, and cleans up, if necessary
     */
    public final void remove(KEY key1, KEY key2) {
        if (key1 == null || key2 == null) {
            return;
        }

        HashMapTree<KEY, VALUE> leaf;
        if (this.children != null) {
            leaf = getLeaf(key1);

            if (leaf != null) {
                leaf.removeLeaf(key2);

                final ConcurrentMap<KEY, HashMapTree<KEY, VALUE>> o = cast(this.children.get());
                o.remove(key1);

                if (o.isEmpty()) {
                    this.children.compareAndSet(o, null);
                }
            }
        }
    }

    /**
     * This <b>IS NOT</b> safe to call outside of root.remove(...)
     * <p>
     * Removes a branch from the tree, and cleans up, if necessary
     */
    public final void remove(KEY key1, KEY key2, KEY key3) {
        if (key1 == null || key2 == null) {
            return;
        }

//        HashMapTree<KEY, VALUE> leaf;
//        if (this.children != null) {
//            leaf = getLeaf(key1);
//
//            if (leaf != null) {
//                leaf.remove(key2, key3);
//                this.children.remove(key1);
//
//                if (this.children.isEmpty()) {
//                    this.children = null;
//                }
//            }
//        }
    }


    /**
     * This <b>IS NOT</b> safe to call outside of root.remove(...)
     * <p>
     * Removes a branch from the tree, and cleans up, if necessary
     */
    @SuppressWarnings("unchecked")
    public final void remove(KEY... keys) {
        if (keys == null) {
            return;
        }

        removeLeaf(0, keys);
    }


    /**
     * Removes a branch from the tree, and cleans up, if necessary
     */
    private void removeLeaf(KEY key) {
        if (key != null) {
            final ConcurrentMap<KEY, HashMapTree<KEY, VALUE>> kids = cast(this.children.get());

            if (kids != null) {
                kids.remove(key);

                if (kids.isEmpty()) {
                    this.children.compareAndSet(kids, null);
                }
            }
        }
    }

    // keys CANNOT be null here!
    private void removeLeaf(int index, KEY[] keys) {
        if (index == keys.length) {
            // we have reached the leaf to remove!
            this.value = null;
            this.children = null;
        } else {
            final ConcurrentMap<KEY, HashMapTree<KEY, VALUE>> kids = cast(this.children.get());
            if (kids != null) {
//                HashMapTree<KEY, VALUE> leaf = this.children.get(keys[index]);

//                if (leaf != null) {
//                    leaf.removeLeaf(index + 1, keys);
//                    if (leaf.children == null && leaf.value == null) {
//                        this.children.remove(keys[index]);
//                    }
//
//                    if (this.children.isEmpty()) {
//                        this.children = null;
//                    }
//                }
            }
        }
    }
}
