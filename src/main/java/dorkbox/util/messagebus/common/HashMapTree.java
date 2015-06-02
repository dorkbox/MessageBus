package dorkbox.util.messagebus.common;

import com.googlecode.concurentlocks.ReentrantReadWriteUpdateLock;

import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;


/**
 * Simple tree structure that is a map that contains a chain of keys to getSubscriptions to a value.
 * <p>
 * THREAD SAFE, each level in the tree has it's own write lock, and there a tree-global read lock, to prevent writes
 *
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class HashMapTree<KEY, VALUE> {
    private final ReentrantReadWriteUpdateLock lock = new ReentrantReadWriteUpdateLock();

    private ConcurrentMap<KEY, HashMapTree<KEY, VALUE>> children; // protected by read/write lock
    private volatile VALUE value; // protected by read/write lock
    private final int defaultSize;
    private final float loadFactor;

    public HashMapTree(int defaultSize, float loadFactor) {
        this.defaultSize = defaultSize;
        this.loadFactor = loadFactor;
    }


    /**
     * can be overridden to provide a custom backing map
     */
    protected ConcurrentMap<KEY, HashMapTree<KEY, VALUE>> createChildren(int defaultSize, float loadFactor) {
        return new ConcurrentHashMapV8<KEY, HashMapTree<KEY, VALUE>>(defaultSize, loadFactor, 1);
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


    public void clear() {
        Lock WRITE = this.lock.writeLock();
        WRITE.lock();  // upgrade to the write lock, at this point blocks other readers

        if (this.children != null) {
            Set<Entry<KEY, HashMapTree<KEY, VALUE>>> entrySet = this.children.entrySet();
            for (Entry<KEY, HashMapTree<KEY, VALUE>> entry : entrySet) {
                entry.getValue().clear();
            }

            this.children.clear();
            this.value = null;
        }

        WRITE.unlock();
    }


    /**
     * This <b>IS NOT</b> safe to call outside of root.remove(...)
     * <p>
     * Removes a branch from the tree, and cleans up, if necessary
     */
    public void remove(KEY key) {
        if (key != null) {
            removeLeaf(key);
        }
    }


    /**
     * This <b>IS NOT</b> safe to call outside of root.remove(...)
     * <p>
     * Removes a branch from the tree, and cleans up, if necessary
     */
    public void remove(KEY key1, KEY key2) {
        if (key1 == null || key2 == null) {
            return;
        }

        Lock UPDATE = this.lock.updateLock();
        UPDATE.lock(); // allows other readers, blocks others from acquiring update or write locks

        HashMapTree<KEY, VALUE> leaf = null;
        if (this.children != null) {
            leaf = this.children.get(key1);

            if (leaf != null) {
                // promote to writelock and try again - Concurrency in Practice,16.4.2, last sentence on page. Careful for stale state
                Lock WRITE = this.lock.writeLock();
                WRITE.lock();  // upgrade to the write lock, at this point blocks other readers

                leaf.removeLeaf(key2);
                this.children.remove(key1);

                if (this.children.isEmpty()) {
                    this.children = null;
                }

                WRITE.unlock();
            }
        }

        UPDATE.unlock();
    }

    /**
     * This <b>IS NOT</b> safe to call outside of root.remove(...)
     * <p>
     * Removes a branch from the tree, and cleans up, if necessary
     */
    public void remove(KEY key1, KEY key2, KEY key3) {
        if (key1 == null || key2 == null) {
            return;
        }

        Lock UPDATE = this.lock.updateLock();
        UPDATE.lock(); // allows other readers, blocks others from acquiring update or write locks

        HashMapTree<KEY, VALUE> leaf = null;
        if (this.children != null) {
            leaf = this.children.get(key1);

            if (leaf != null) {
                // promote to writelock and try again - Concurrency in Practice,16.4.2, last sentence on page. Careful for stale state
                Lock WRITE = this.lock.writeLock();
                WRITE.lock();  // upgrade to the write lock, at this point blocks other readers

                leaf.remove(key2, key3);
                this.children.remove(key1);

                if (this.children.isEmpty()) {
                    this.children = null;
                }

                WRITE.unlock();
            }
        }

        UPDATE.unlock();
    }


    /**
     * This <b>IS NOT</b> safe to call outside of root.remove(...)
     * <p>
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
            Lock WRITE = this.lock.writeLock();
            WRITE.lock();  // upgrade to the write lock, at this point blocks other readers

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

            WRITE.unlock();
        }
    }

    // keys CANNOT be null here!
    private final void removeLeaf(int index, KEY[] keys) {
        Lock WRITE = this.lock.writeLock();
        WRITE.lock();  // upgrade to the write lock, at this point blocks other readers

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

        WRITE.unlock();
    }

    public VALUE put(VALUE value, KEY key) {
        if (key == null) {
            throw new NullPointerException("keys");
        }

        Lock WRITE = this.lock.writeLock();
        WRITE.lock();  // upgrade to the write lock, at this point blocks other readers

        // have to put value into our children
        HashMapTree<KEY, VALUE> leaf = createLeaf_NL(key);
        VALUE prev = leaf.value;
        leaf.value = value;

        WRITE.unlock();

        return prev;
    }

    public VALUE putIfAbsent(VALUE value, KEY key) {
        if (key == null) {
            throw new NullPointerException("keys");
        }

        Lock WRITE = this.lock.writeLock();
        WRITE.lock();  // upgrade to the write lock, at this point blocks other readers

        // have to put value into our children
        HashMapTree<KEY, VALUE> leaf = createLeaf_NL(key);

        VALUE prev = leaf.value;
        if (prev == null) {
            leaf.value = value;
        }

        WRITE.unlock();

        return prev;
    }

    public VALUE put(VALUE value, KEY key1, KEY key2) {
        if (key1 == null || key2 == null) {
            throw new NullPointerException("keys");
        }

        Lock WRITE = this.lock.writeLock();
        WRITE.lock();  // upgrade to the write lock, at this point blocks other readers

        // have to put value into our children
        HashMapTree<KEY, VALUE> leaf = createLeaf_NL(key1);
        Lock WRITE2 = leaf.lock.writeLock();
        WRITE2.lock();
        leaf = leaf.createLeaf_NL(key2);
        WRITE2.unlock();

        VALUE prev = leaf.value;
        leaf.value = value;

        WRITE.unlock();

        return prev;
    }

    public VALUE putIfAbsent(VALUE value, KEY key1, KEY key2) {
        if (key1 == null || key2 == null) {
            throw new NullPointerException("keys");
        }

        Lock WRITE = this.lock.writeLock();
        WRITE.lock();  // upgrade to the write lock, at this point blocks other readers

        // have to put value into our children
        HashMapTree<KEY, VALUE> leaf = createLeaf_NL(key1);
        Lock WRITE2 = leaf.lock.writeLock();
        WRITE2.lock();
        leaf = leaf.createLeaf_NL(key2);
        WRITE2.unlock();

        VALUE prev = leaf.value;
        if (prev == null) {
            leaf.value = value;
        }

        WRITE.unlock();

        return prev;
    }

    public VALUE put(VALUE value, KEY key1, KEY key2, KEY key3) {
        if (key1 == null || key2 == null || key3 == null) {
            throw new NullPointerException("keys");
        }

        Lock WRITE = this.lock.writeLock();
        WRITE.lock();  // upgrade to the write lock, at this point blocks other readers

        // have to put value into our children
        HashMapTree<KEY, VALUE> leaf = createLeaf_NL(key1);
        Lock WRITE2 = leaf.lock.writeLock();
        WRITE2.lock();
        leaf = leaf.createLeaf_NL(key2);
        Lock WRITE3 = leaf.lock.writeLock();
        WRITE3.lock();
        leaf = leaf.createLeaf_NL(key3);
        WRITE3.unlock();
        WRITE2.unlock();

        VALUE prev = leaf.value;
        leaf.value = value;

        WRITE.unlock();

        return prev;
    }

    public VALUE putIfAbsent(VALUE value, KEY key1, KEY key2, KEY key3) {
        if (key1 == null || key2 == null || key3 == null) {
            throw new NullPointerException("keys");
        }

        Lock WRITE = this.lock.writeLock();
        WRITE.lock();  // upgrade to the write lock, at this point blocks other readers

        // have to put value into our children
        HashMapTree<KEY, VALUE> leaf = createLeaf_NL(key1);
        Lock WRITE2 = leaf.lock.writeLock();
        WRITE2.lock();
        leaf = leaf.createLeaf_NL(key2);
        Lock WRITE3 = leaf.lock.writeLock();
        WRITE3.lock();
        leaf = leaf.createLeaf_NL(key3);
        WRITE3.unlock();
        WRITE2.unlock();

        VALUE prev = leaf.value;
        if (prev == null) {
            leaf.value = value;
        }

        WRITE.unlock();

        return prev;
    }

    @SuppressWarnings("unchecked")
    public VALUE put(VALUE value, KEY... keys) {
        if (keys == null) {
            throw new NullPointerException("keys");
        }

        int length = keys.length;
        Lock[] locks = new Lock[length];

        Lock WRITE = this.lock.writeLock();
        WRITE.lock();  // upgrade to the write lock, at this point blocks other readers

        // have to put value into our children
        HashMapTree<KEY, VALUE> leaf = createLeaf_NL(keys[0]);
        for (int i=1;i<length;i++) {
            locks[i] = leaf.lock.writeLock();
            locks[i].lock();
            leaf = leaf.createLeaf_NL(keys[i]);
        }

        for (int i=length-1;i>0;i--) {
            locks[i].unlock();
        }

        VALUE prev = leaf.value;
        leaf.value = value;

        WRITE.unlock();

        return prev;
    }

    @SuppressWarnings("unchecked")
    public VALUE putIfAbsent(VALUE value, KEY... keys) {
        if (keys == null) {
            throw new NullPointerException("keys");
        }

        int length = keys.length;
        Lock[] locks = new Lock[length];

        Lock WRITE = this.lock.writeLock();
        WRITE.lock();  // upgrade to the write lock, at this point blocks other readers

        // have to put value into our children
        HashMapTree<KEY, VALUE> leaf = createLeaf_NL(keys[0]);
        for (int i=1;i<length;i++) {
            locks[i] = leaf.lock.writeLock();
            locks[i].lock();
            leaf = leaf.createLeaf_NL(keys[i]);
        }

        for (int i=length-1;i>0;i--) {
            locks[i].unlock();
        }

        VALUE prev = leaf.value;
        if (prev == null) {
            leaf.value = value;
        }

        WRITE.unlock();

        return prev;
    }

    @SuppressWarnings("unchecked")
    public HashMapTree<KEY, VALUE> createLeaf(KEY... keys) {
        if (keys == null) {
            return this;
        }
        int length = keys.length;
        Lock[] locks = new Lock[length];

        Lock WRITE = this.lock.writeLock();
        WRITE.lock();  // upgrade to the write lock, at this point blocks other readers

        // have to put value into our children
        HashMapTree<KEY, VALUE> leaf = createLeaf_NL(keys[0]);
        for (int i=1;i<length;i++) {
            locks[i] = leaf.lock.writeLock();
            locks[i].lock();
            leaf = leaf.createLeaf_NL(keys[i]);
        }

        for (int i=length-1;i>0;i--) {
            locks[i].unlock();
        }

        WRITE.unlock();

        return leaf;
    }


    private final HashMapTree<KEY, VALUE> createLeaf_NL(KEY key) {
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
            HashMapTree<KEY, VALUE> putIfAbsent = this.children.putIfAbsent(key, objectTree);
            if (putIfAbsent != null) {
                // some other thread beat us.
                objectTree = putIfAbsent;
            }
        }

        return objectTree;
    }


    /////////////////////////////////////////
    /////////////////////////////////////////
    /////////////////////////////////////////
    /////////////////////////////////////////

    public VALUE get(KEY key) {
        if (key == null) {
            return null;
        }

        Lock READ = this.lock.readLock();
        READ.lock(); // allows other readers, blocks others from acquiring update or write locks

        HashMapTree<KEY, VALUE> objectTree = null;
        // getSubscriptions value from our children
        objectTree = getLeaf_NL(key); // protected by lock

        if (objectTree == null) {
            READ.unlock();
            return null;
        }

        VALUE returnValue = objectTree.value;

        READ.unlock();
        return returnValue;
    }

    public VALUE get(KEY key1, KEY key2) {
        Lock READ = this.lock.readLock();
        READ.lock(); // allows other readers, blocks others from acquiring update or write locks

        HashMapTree<KEY, VALUE> tree = null;
        // getSubscriptions value from our children
        tree = getLeaf_NL(key1); // protected by lock
        if (tree != null) {
            tree = tree.getLeaf_NL(key2); // protected by lock
        }

        if (tree == null) {
            READ.unlock();
            return null;
        }

        VALUE returnValue = tree.value;

        READ.unlock();
        return returnValue;
    }

    public VALUE getValue(KEY key1, KEY key2, KEY key3) {
        Lock READ = this.lock.readLock();
        READ.lock(); // allows other readers, blocks others from acquiring update or write locks

        HashMapTree<KEY, VALUE> tree = null;
        // getSubscriptions value from our children
        tree = getLeaf_NL(key1);
        if (tree != null) {
            tree = tree.getLeaf_NL(key2);
        }
        if (tree != null) {
            tree = tree.getLeaf_NL(key3);
        }

        if (tree == null) {
            READ.unlock();
            return null;
        }

        VALUE returnValue = tree.value;

        READ.unlock();
        return returnValue;
    }

    @SuppressWarnings("unchecked")
    public VALUE get(KEY... keys) {
        Lock READ = this.lock.readLock();
        READ.lock(); // allows other readers, blocks others from acquiring update or write locks

        HashMapTree<KEY, VALUE> tree = null;
        // getSubscriptions value from our children
        tree = getLeaf_NL(keys[0]);

        int size = keys.length;
        for (int i=1;i<size;i++) {
            if (tree != null) {
                tree = tree.getLeaf_NL(keys[i]);
            } else {
                READ.unlock();
                return null;
            }
        }

        if (tree == null) {
            READ.unlock();
            return null;
        }

        VALUE returnValue = tree.value;

        READ.unlock();

        return returnValue;
    }

    public final HashMapTree<KEY, VALUE> getLeaf(KEY key) {
        if (key == null) {
            return null;
        }

        HashMapTree<KEY, VALUE> tree;

        Lock READ = this.lock.readLock();
        READ.lock(); // allows other readers, blocks others from acquiring update or write locks

        if (this.children == null) {
            tree = null;
        } else {
            tree = this.children.get(key);
        }

        READ.unlock();

        return tree;
    }

    public final HashMapTree<KEY, VALUE> getLeaf(KEY key1, KEY key2) {
        HashMapTree<KEY, VALUE> tree = null;

        Lock READ = this.lock.readLock();
        READ.lock(); // allows other readers, blocks others from acquiring update or write locks

        // getSubscriptions value from our children
        tree = getLeaf_NL(key1);
        if (tree != null) {
            tree = tree.getLeaf_NL(key2);
        }

        READ.unlock();

        return tree;
    }

    public final HashMapTree<KEY, VALUE> getLeaf(KEY key1, KEY key2, KEY key3) {
        HashMapTree<KEY, VALUE> tree = null;

        Lock READ = this.lock.readLock();
        READ.lock(); // allows other readers, blocks others from acquiring update or write locks

        // getSubscriptions value from our children
        tree = getLeaf_NL(key1);
        if (tree != null) {
            tree = tree.getLeaf_NL(key2);
        }
        if (tree != null) {
            tree = tree.getLeaf_NL(key3);
        }

        READ.unlock();

        return tree;
    }

    @SuppressWarnings("unchecked")
    public final HashMapTree<KEY, VALUE> getLeaf(KEY... keys) {
        int size = keys.length;

        if (size == 0) {
            return null;
        }

        Lock READ = this.lock.readLock();
        READ.lock(); // allows other readers, blocks others from acquiring update or write locks

        HashMapTree<KEY, VALUE> tree = null;
        // getSubscriptions value from our children
        tree = getLeaf_NL(keys[0]);

        for (int i=1;i<size;i++) {
            if (tree != null) {
                tree = tree.getLeaf_NL(keys[i]);
            } else {
                READ.unlock();
                return null;
            }
        }

        READ.unlock();

        return tree;
    }

    private final HashMapTree<KEY, VALUE> getLeaf_NL(KEY key) {
        HashMapTree<KEY, VALUE> tree;

        if (this.children == null) {
            tree = null;
        } else {
            tree = this.children.get(key);
        }

        return tree;
    }
}
