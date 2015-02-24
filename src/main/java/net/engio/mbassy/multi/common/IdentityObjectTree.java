package net.engio.mbassy.multi.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Simple tree structure that is a map that contains a chain of keys to get to a value.
 * <p>
 * NOT THREAD SAFE
 * <p>
 * Comparisons for the KEY are '==', NOT '.equals()'
 *
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class IdentityObjectTree<KEY, VALUE> {
    private Map<KEY, IdentityObjectTree<KEY, VALUE>> children;
    private volatile VALUE value;

    public IdentityObjectTree() {
    }

    /**
     * can be overridden to provide a custom backing map
     */
    protected Map<KEY, IdentityObjectTree<KEY, VALUE>> createChildren() {
        return new ConcurrentHashMap<KEY, IdentityObjectTree<KEY, VALUE>>(2, 0.75f, 1);
    }

    public VALUE getValue() {
        VALUE returnValue = this.value;
        return returnValue;
    }

    public void putValue(VALUE value) {
        this.value = value;
    }

    public void removeValue() {
        this.value = null;
    }

    /**
     * Removes a branch from the tree, and cleans up, if necessary
     */
    public void remove(KEY key) {
        if (key == null) {
            removeLeaf(key);
        }
    }

    /**
     * Removes a branch from the tree, and cleans up, if necessary
     */
    public void remove(KEY key1, KEY key2) {
        if (key1 == null || key2 == null) {
            return;
        }

        IdentityObjectTree<KEY, VALUE> leaf = null;
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
     * Removes a branch from the tree, and cleans up, if necessary
     */
    public void remove(KEY key1, KEY key2, KEY key3) {
        if (key1 == null || key2 == null) {
            return;
        }

        IdentityObjectTree<KEY, VALUE> leaf = null;
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
     * Removes a branch from the tree, and cleans up, if necessary
     */
    @SuppressWarnings("unchecked")
    public void remove(KEY... keys) {
        if (keys == null) {
            return;
        }

        removeLeaf(0, keys);
    }

    /**
     * Removes a branch from the tree, and cleans up, if necessary
     */
    private final void removeLeaf(KEY key) {
        if (key != null) {
            if (this.children != null) {
                IdentityObjectTree<KEY, VALUE> leaf = this.children.get(key);
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

    // keys CANNOT be null here!
    private final void removeLeaf(int index, KEY[] keys) {
        if (index == keys.length) {
            // we have reached the leaf to remove!
            this.value = null;
            this.children = null;
        } else if (this.children != null) {
            IdentityObjectTree<KEY, VALUE> leaf = this.children.get(keys[index]);
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

    /**
     * BACKWARDS, because our signature must allow for N keys...
     */
    public void put(VALUE value, KEY key) {
        // have to put value into our children
        createLeaf(key, value, true);
    }

    /**
     * BACKWARDS, because our signature must allow for N keys...
     */
    public void put(VALUE value, KEY key1, KEY key2) {
        // have to put value into our children
        IdentityObjectTree<KEY, VALUE> leaf = createLeaf(key1, value, false);
        if (leaf != null) {
            leaf.createLeaf(key2, value, true);
        }
    }

    /**
     * BACKWARDS, because our signature must allow for N keys...
     */
    public void put(VALUE value, KEY key1, KEY key2, KEY key3) {
        // have to put value into our children
        IdentityObjectTree<KEY, VALUE> leaf = createLeaf(key1, value, false);
        if (leaf != null) {
            leaf = leaf.createLeaf(key2, value, false);
        }
        if (leaf != null) {
            leaf.createLeaf(key3, value, true);
        }
    }

    /**
     * BACKWARDS, because our signature must allow for N keys...
     */
    @SuppressWarnings("unchecked")
    public void put(VALUE value, KEY... keys) {
        if (keys == null) {
            return;
        }

        int length = keys.length;
        int length_1 = length - 1;
        boolean setFirstValue = length == 1;

        // have to put value into our children
        IdentityObjectTree<KEY, VALUE> leaf = createLeaf(keys[0], value, setFirstValue);
        for (int i=1;i<length;i++) {
            if (leaf != null) {
                leaf = leaf.createLeaf(keys[i], value, i == length_1);
            }
        }
    }

    /**
     * BACKWARDS, because our signature must allow for N keys...
     */
    @SuppressWarnings("unchecked")
    public IdentityObjectTree<KEY, VALUE> createLeaf(KEY... keys) {
        if (keys == null) {
            return this;
        }
        int length = keys.length;

        // have to put value into our children
        IdentityObjectTree<KEY, VALUE> leaf = createLeaf(keys[0], null, false);
        for (int i=1;i<length;i++) {
            if (leaf != null) {
                leaf = leaf.createLeaf(keys[i], null, false);
            }
        }

        return leaf;
    }

    public final IdentityObjectTree<KEY, VALUE> createLeaf(KEY key, VALUE value, boolean setValue) {
        if (key == null) {
            return null;
        }

        IdentityObjectTree<KEY, VALUE> objectTree;

        if (this.children == null) {
            this.children = createChildren();

            // might as well add too
            objectTree = new IdentityObjectTree<KEY, VALUE>();
            if (setValue) {
                objectTree.value = value;
            }

            this.children.put(key, objectTree);
        } else {
            objectTree = this.children.get(key);

            // make sure we have a tree for the specified node
            if (objectTree == null) {
                objectTree = new IdentityObjectTree<KEY, VALUE>();
                if (setValue) {
                    objectTree.value = value;
                }

                this.children.put(key, objectTree);
            } else if (setValue) {
                objectTree.value = value;
            }
        }
        return objectTree;
    }


    /////////////////////////////////////////
    /////////////////////////////////////////
    /////////////////////////////////////////
    /////////////////////////////////////////


    public VALUE get(KEY key) {
        IdentityObjectTree<KEY, VALUE> objectTree = null;
        // get value from our children
        objectTree = getLeaf(key);

        if (objectTree == null) {
            return null;
        }

        VALUE returnValue = objectTree.value;
        return returnValue;
    }

    public VALUE getValue(KEY key1, KEY key2) {
        IdentityObjectTree<KEY, VALUE> tree = null;
        // get value from our children
        tree = getLeaf(key1);
        if (tree != null) {
            tree = tree.getLeaf(key2);
        }

        if (tree == null) {
            return null;
        }

        VALUE returnValue = tree.value;
        return returnValue;
    }

    public VALUE getValue(KEY key1, KEY key2, KEY key3) {
        IdentityObjectTree<KEY, VALUE> tree = null;
        // get value from our children
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

        VALUE returnValue = tree.value;
        return returnValue;
    }

    @SuppressWarnings("unchecked")
    public VALUE getValue(KEY... keys) {
        IdentityObjectTree<KEY, VALUE> tree = null;
        // get value from our children
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

        VALUE returnValue = tree.value;
        return returnValue;
    }

    public final IdentityObjectTree<KEY, VALUE> getLeaf(KEY key) {
        if (key == null) {
            return null;
        }

        IdentityObjectTree<KEY, VALUE> tree;

        if (this.children == null) {
            tree = null;
        } else {
            tree = this.children.get(key);
        }

        return tree;
    }

    public final IdentityObjectTree<KEY, VALUE> getLeaf(KEY key1, KEY key2) {
        IdentityObjectTree<KEY, VALUE> tree = null;
        // get value from our children
        tree = getLeaf(key1);
        if (tree != null) {
            tree = tree.getLeaf(key2);
        }

        if (tree == null) {
            return null;
        }

        return tree;
    }

    public final IdentityObjectTree<KEY, VALUE> getLeaf(KEY key1, KEY key2, KEY key3) {
        IdentityObjectTree<KEY, VALUE> tree = null;
        // get value from our children
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

        return tree;
    }

    @SuppressWarnings("unchecked")
    public final IdentityObjectTree<KEY, VALUE> getLeaf(KEY... keys) {
        int size = keys.length;

        if (size == 0) {
            return null;
        }

        IdentityObjectTree<KEY, VALUE> tree = null;
        // get value from our children
        tree = getLeaf(keys[0]);

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

        return tree;
    }
}