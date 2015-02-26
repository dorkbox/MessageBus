package dorkbox.util.messagebus.common;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;

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
public abstract class AbstractConcurrentSet<T> implements Set<T> {
    private static final AtomicLong id = new AtomicLong();
    private final transient long ID = id.getAndIncrement();

    // Internal state
    protected final transient ReentrantReadWriteUpdateLock lock = new ReentrantReadWriteUpdateLock();
    private final transient Map<T, ISetEntry<T>> entries; // maintain a map of entries for O(log n) lookup
    protected transient Entry<T> head; // reference to the first element

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

        Lock writeLock = this.lock.writeLock();
        writeLock.lock();
        changed = insert(element);
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
        boolean changed = false;
        Lock writeLock = this.lock.writeLock();
        try {
            writeLock.lock();
            for (T element : elements) {
                if (element != null) {
                    changed |= insert(element);
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
        try {
            updateLock.lock();
            ISetEntry<T> entry = this.entries.get(element);

            if (entry != null && entry.getValue() != null) {
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
        return this.entries.entrySet().toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
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
        Lock writeLock = this.lock.writeLock();
        try {
            writeLock.lock();
                this.head = null;
                this.entries.clear();
        } finally {
            writeLock.unlock();
        }
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
        AbstractConcurrentSet other = (AbstractConcurrentSet) obj;
        if (this.ID != other.ID) {
            return false;
        }
        return true;
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
