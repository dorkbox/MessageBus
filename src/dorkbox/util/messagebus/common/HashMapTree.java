package dorkbox.util.messagebus.common;

import dorkbox.util.messagebus.common.adapter.JavaVersionAdapter;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;


/**
 * Simple tree structure that is a map that contains a chain of keys to publish to a value.
 * <p>
 * NOT THREAD SAFE, each call must be protected by a read/write lock of some sort
 *
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class HashMapTree<KEY, VALUE> {
    private Map<KEY, HashMapTree<KEY, VALUE>> children;
    private VALUE value;

    private final int defaultSize;
    private final float loadFactor;

    public HashMapTree(final int defaultSize, final float loadFactor) {
        this.defaultSize = defaultSize;
        this.loadFactor = loadFactor;
    }


    /**
     * can be overridden to provide a custom backing map
     */
    protected Map<KEY, HashMapTree<KEY, VALUE>> createChildren(int defaultSize, float loadFactor) {
        return JavaVersionAdapter.concurrentMap(defaultSize, loadFactor, 1);
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
        if (this.children != null) {
            Set<Entry<KEY, HashMapTree<KEY, VALUE>>> entrySet = this.children.entrySet();
            for (Entry<KEY, HashMapTree<KEY, VALUE>> entry : entrySet) {
                entry.getValue().clear();
            }

            this.children.clear();
            this.value = null;
        }
    }


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
            leaf = this.children.get(key1);

            if (leaf != null) {
                leaf.removeLeaf(key2);
                this.children.remove(key1);

                if (this.children.isEmpty()) {
                    this.children = null;
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

        HashMapTree<KEY, VALUE> leaf;
        if (this.children != null) {
            leaf = this.children.get(key1);

            if (leaf != null) {
                leaf.remove(key2, key3);
                this.children.remove(key1);

                if (this.children.isEmpty()) {
                    this.children = null;
                }
            }
        }
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
            if (this.children != null) {
                HashMapTree<KEY, VALUE> leaf = this.children.get(key);

                if (leaf != null) {
                    leaf = this.children.get(key);
                    if (leaf != null) {
                        if (leaf.children == null && leaf.value == null) {
                            this.children.remove(key);
                        }

                        if (this.children.isEmpty()) {
                            this.children = null;
                        }
                    }
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
        } else if (this.children != null) {
            HashMapTree<KEY, VALUE> leaf = this.children.get(keys[index]);

            if (leaf != null) {
                leaf.removeLeaf(index+1, keys);
                if (leaf.children == null && leaf.value == null) {
                    this.children.remove(keys[index]);
                }

                if (this.children.isEmpty()) {
                    this.children = null;
                }
            }
        }
    }

    public final VALUE put(VALUE value, KEY key) {
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


    private HashMapTree<KEY, VALUE> createLeaf(KEY key) {
        if (key == null) {
            return null;
        }

        HashMapTree<KEY, VALUE> objectTree;

        if (this.children == null) {
            this.children = createChildren(this.defaultSize, this.loadFactor);
        }

        objectTree = this.children.get(key);

        // make sure we have a tree for the specified node
        if (objectTree == null) {
            objectTree = new HashMapTree<KEY, VALUE>(this.defaultSize, this.loadFactor);
            this.children.put(key, objectTree);
        }

        return objectTree;
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
        objectTree = getLeaf(key); // protected by lock

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
            tree = tree.getLeaf(key2); // protected by lock
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
            tree = this.children.get(key);
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
}
