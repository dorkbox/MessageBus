package net.engio.mbassy.multi.common;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import com.googlecode.concurentlocks.ReentrantReadWriteUpdateLock;

/**
 * This data structure is optimized for non-blocking reads even when write operations occur.
 * Running read iterators will not be affected by add operations since writes always insert at the head of the
 * structure. Remove operations can affect any running iterator such that a removed element that has not yet
 * been reached by the iterator will not appear in that iterator anymore.
 *
 * @author bennidi
 *         Date: 2/12/12
 */
public abstract class AbstractConcurrentSet<T> implements Collection<T> {

    // Internal state
    protected final ReentrantReadWriteUpdateLock lock = new ReentrantReadWriteUpdateLock();
    private final Map<T, ISetEntry<T>> entries; // maintain a map of entries for O(log n) lookup
    protected Entry<T> head; // reference to the first element

    protected AbstractConcurrentSet(Map<T, ISetEntry<T>> entries) {
        this.entries = entries;
    }

    protected abstract Entry<T> createEntry(T value, Entry<T> next);

    @Override
    public boolean add(T element) {
        if (element == null) {
            return false;
        }
        Lock writeLock = this.lock.writeLock();
        boolean changed = false;
        writeLock.lock();
        if (this.entries.containsKey(element)) {
        } else {
            insert(element);
            changed = true;
        }
        writeLock.unlock();

        return changed;
    }

    @Override
    public boolean contains(Object element) {
        Lock readLock = this.lock.readLock();
        ISetEntry<T> entry;
        try {
            readLock.lock();
            entry = this.entries.get(element);

        } finally {
            readLock.unlock();
        }
        return entry != null && entry.getValue() != null;
    }

    private void insert(T element) {
        if (!this.entries.containsKey(element)) {
            this.head = createEntry(element, this.head);
            this.entries.put(element, this.head);
        }
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
        boolean changed = false;
        Lock writeLock = this.lock.writeLock();
        try {
            writeLock.lock();
            for (T element : elements) {
                if (element != null) {
                    insert(element);
                    changed = true;
                }
            }
        } finally {
            writeLock.unlock();
        }
        return changed;
    }

    /**
     * @return TRUE if the element was successfully removed
     */
    @Override
    public boolean remove(Object element) {

        Lock updateLock = this.lock.updateLock();
        boolean isNull;
        try {
            updateLock.lock();
            ISetEntry<T> entry = this.entries.get(element);

            isNull = entry == null || entry.getValue() == null;
            if (!isNull) {
                Lock writeLock = this.lock.writeLock();
                try {
                    writeLock.lock();
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
                    writeLock.unlock();
                }
            } else {
                return false; // fast exit
            }
        } finally {
            updateLock.unlock();
        }
    }

    @Override
    public Object[] toArray() {
        throw new NotImplementedException();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        throw new NotImplementedException();
    }

    @Override
    public boolean containsAll(Collection<?> c) {
       throw new NotImplementedException();
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        throw new NotImplementedException();
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new NotImplementedException();
    }

    @Override
    public void clear() {
        throw new NotImplementedException();
    }


    public abstract static class Entry<T> implements ISetEntry<T> {

        private Entry<T> next;

        private Entry<T> predecessor;

        protected Entry(Entry<T> next) {
            this.next = next;
            next.predecessor = this;
        }

        protected Entry() {
        }

        // not thread-safe! must be synchronized in enclosing context
        @Override
        public void remove() {
            if (this.predecessor != null) {
                this.predecessor.next = this.next;
                if (this.next != null) {
                    this.next.predecessor = this.predecessor;
                }
            } else if (this.next != null) {
                this.next.predecessor = null;
            }
            // can not nullify references to help GC since running iterators might not see the entire set
            // if this element is their current element
            //next = null;
            //predecessor = null;
        }

        @Override
        public Entry<T> next() {
            return this.next;
        }

        @Override
        public void clear() {
            this.next = null;
        }
    }
}
