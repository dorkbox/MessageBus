package dorkbox.util.messagebus.common;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import dorkbox.util.messagebus.common.thread.StampedLock;


abstract class pad<T> extends item<T> {
    volatile long z0, z1, z2, z4, z5, z6 = 7L;
}

/**
 * This data structure is optimized for non-blocking reads even when write operations occur.
 * Running read iterators will not be affected by add operations since writes always insert at the head of the
 * structure. Remove operations can affect any running iterator such that a removed element that has not yet
 * been reached by the iterator will not appear in that iterator anymore.
 *
 * @author bennidi
 *         Date: 2/12/12
 */
public abstract class AbstractConcurrentSet<T> extends pad<T> implements Set<T> {
    private static final AtomicLong id = new AtomicLong();
    private final transient long ID = id.getAndIncrement();

    // Internal state
    protected final StampedLock lock = new StampedLock();
    private final transient Map<T, ISetEntry<T>> entries; // maintain a map of entries for O(log n) lookup

    volatile long y0, y1, y2, y4, y5, y6 = 7L;
    volatile long z0, z1, z2, z4, z5, z6 = 7L;


    protected AbstractConcurrentSet(Map<T, ISetEntry<T>> entries) {
        this.entries = entries;
    }

    protected abstract Entry<T> createEntry(T value, Entry<T> next);

    @Override
    public boolean add(T element) {
        if (element == null) {
            return false;
        }
        boolean changed = false;

        long stamp = this.lock.readLock();
        if (this.entries.containsKey(element)) {
            this.lock.unlockRead(stamp);
            return false;
        }

        long newStamp = 0L;
        while ((newStamp = this.lock.tryConvertToWriteLock(stamp)) == 0) {
            this.lock.unlockRead(stamp);
            stamp = this.lock.writeLock();
        }
        stamp = newStamp;


        changed = insert(element);
        this.lock.unlock(stamp);

        return changed;
    }

    @Override
    public boolean contains(Object element) {
        long stamp = this.lock.tryOptimisticRead();

        ISetEntry<T> entry = this.entries.get(element);

        if (!this.lock.validate(stamp)) {
            stamp = this.lock.readLock();
            entry = this.entries.get(element);
            this.lock.unlockRead(stamp);
        }

        return entry != null && entry.getValue() != null;
    }

    private boolean insert(T element) {
        if (!this.entries.containsKey(element)) {
            this.head = createEntry(element, this.head);
            this.entries.put(element, this.head);
            return true;
        }
        return false;
    }

    @Override
    public int size() {
        return this.entries.size();
    }

    @Override
    public boolean isEmpty() {
        return this.head == null;
    }

    @Override
    public boolean addAll(Collection<? extends T> elements) {
        StampedLock lock = this.lock;

        boolean changed = false;
        long stamp = lock.writeLock();

        try {
            for (T element : elements) {
                if (element != null) {
                    changed |= insert(element);
                }
            }
        } finally {
            lock.unlockWrite(stamp);
        }

        return changed;
    }

    /**
     * @return TRUE if the element was successfully removed
     */
    @Override
    public boolean remove(Object element) {
        StampedLock lock = this.lock;
        long stamp = lock.tryOptimisticRead();

        ISetEntry<T> entry = this.entries.get(element);

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            entry = this.entries.get(element);
            lock.unlockRead(stamp);
        }

        if (entry == null || entry.getValue() == null) {
            return false; // fast exit
        } else {
            stamp = lock.writeLock();

            try {
                if (entry != this.head) {
                    entry.remove();
                } else {
                    // if it was second, now it's first
                    this.head = this.head.next();
                    //oldHead.clear(); // optimize for GC not possible because of potentially running iterators
                }
                this.entries.remove(element);
                return true;
            } finally {
                lock.unlockWrite(stamp);
            }
        }
    }

    @Override
    public Object[] toArray() {
        return this.entries.entrySet().toArray();
    }

    @Override
    public <T2> T2[] toArray(T2[] a) {
        return this.entries.entrySet().toArray(a);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void clear() {
        StampedLock lock = this.lock;

        long stamp = lock.writeLock();
        this.head = null;
        this.entries.clear();
        lock.unlockWrite(stamp);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (int) (this.ID ^ this.ID >>> 32);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        @SuppressWarnings("rawtypes")
        AbstractConcurrentSet other = (AbstractConcurrentSet) obj;
        if (this.ID != other.ID) {
            return false;
        }
        return true;
    }


}
