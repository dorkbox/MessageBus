/*
 * Copyright 2012 Benjamin Diedrichsen
 *
 * Permission is hereby granted, free  of charge, to any person obtaining
 * a  copy  of this  software  and  associated  documentation files  (the
 * "Software"), to  deal in  the Software without  restriction, including
 * without limitation  the rights to  use, copy, modify,  merge, publish,
 * distribute,  sublicense, and/or sell  copies of  the Software,  and to
 * permit persons to whom the Software  is furnished to do so, subject to
 * the following conditions:
 *
 * The  above  copyright  notice  and  this permission  notice  shall  be
 * included in all copies or substantial portions of the Software.
 *
 * THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
 * EXPRESS OR  IMPLIED, INCLUDING  BUT NOT LIMITED  TO THE  WARRANTIES OF
 * MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE,  ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package dorkbox.util.messagebus.common;

import dorkbox.util.messagebus.common.adapter.StampedLock;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This data structure is optimized for non-blocking reads even when write operations occur.
 * Running read iterators will not be affected by add operations since writes always insert at the head of the
 * structure. Remove operations can affect any running iterator such that a removed element that has not yet
 * been reached by the iterator will not appear in that iterator anymore.
 *
 * @author bennidi
 *         Date: 2/12/12
 */
public abstract
class AbstractConcurrentSet<T> implements Set<T> {
    private static final AtomicLong id = new AtomicLong();
    private final transient long ID = id.getAndIncrement();

    // Internal state
    protected final StampedLock lock = new StampedLock();
    private final Map<T, ISetEntry<T>> entries; // maintain a map of entries for O(log n) lookup

    public volatile Entry<T> head; // reference to the first element
    volatile long z0, z1, z2, z4, z5, z6 = 7L;


    protected
    AbstractConcurrentSet(Map<T, ISetEntry<T>> entries) {
        this.entries = entries;
    }

    protected abstract
    Entry<T> createEntry(T value, Entry<T> next);

    @Override
    public
    boolean add(T element) {
        if (element == null) {
            return false;
        }
        boolean changed;

        final StampedLock lock = this.lock;
        long stamp = lock.readLock();
        if (this.entries.containsKey(element)) {
            lock.unlockRead(stamp);
            return false;
        }

        long origStamp = stamp;
        if ((stamp = lock.tryConvertToWriteLock(stamp)) == 0) {
            lock.unlockRead(origStamp);
            stamp = lock.writeLock();
        }


        changed = insert(element);
        lock.unlock(stamp);

        return changed;
    }

    @Override
    public
    boolean contains(Object element) {
        final StampedLock lock = this.lock;
        long stamp = lock.readLock();

        ISetEntry<T> entry = this.entries.get(element);

        lock.unlockRead(stamp);

        return entry != null && entry.getValue() != null;
    }

    private
    boolean insert(T element) {
        if (!this.entries.containsKey(element)) {
            this.head = createEntry(element, this.head);
            this.entries.put(element, this.head);
            return true;
        }
        return false;
    }

    @Override
    public
    int size() {
        return this.entries.size();
    }

    @Override
    public
    boolean isEmpty() {
        return this.head == null;
    }

    @Override
    public
    boolean addAll(Collection<? extends T> elements) {
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
    public
    boolean remove(Object element) {
        StampedLock lock = this.lock;
        long stamp = lock.readLock();

        ISetEntry<T> entry = this.entries.get(element);

        lock.unlockRead(stamp);

        if (entry == null || entry.getValue() == null) {
            return false; // fast exit
        }
        else {
            stamp = lock.writeLock();

            try {
                if (entry != this.head) {
                    entry.remove();
                }
                else {
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
    public
    Object[] toArray() {
        return this.entries.entrySet().toArray();
    }

    @Override
    public
    <T2> T2[] toArray(T2[] a) {
        return this.entries.entrySet().toArray(a);
    }

    @Override
    public
    boolean containsAll(Collection<?> c) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public
    boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public
    boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public
    void clear() {
        StampedLock lock = this.lock;

        long stamp = lock.writeLock();
        this.head = null;
        this.entries.clear();
        lock.unlockWrite(stamp);
    }

    @Override
    public
    int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (int) (this.ID ^ this.ID >>> 32);
        return result;
    }

    @Override
    public
    boolean equals(Object obj) {
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
