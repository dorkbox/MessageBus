package dorkbox.util.messagebus.common.thread;

import dorkbox.util.messagebus.common.ConcurrentHashMapV8;

import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This data structure is optimized for non-blocking reads even when write operations occur.
 * Running read iterators will not be affected by add operations since writes always insert at the head of the
 * structure. Remove operations can affect any running iterator such that a removed element that has not yet
 * been reached by the iterator will not appear in that iterator anymore.
 */
public class ConcurrentSet<T> extends ConcurrentLinkedQueue2<T> {
    private static final long serialVersionUID = -2729855178402529784L;

    private static final AtomicLong id = new AtomicLong();
    private final transient long ID = id.getAndIncrement();


    private final Node<T> IN_PROGRESS_MARKER = new Node<T>(null);
    private ConcurrentMap<T, Node<T>> entries;

    public ConcurrentSet() {
        this(16, 0.75f, Runtime.getRuntime().availableProcessors());
    }

    public ConcurrentSet(int size, float loadFactor, int stripeSize) {
        super();
        this.entries = new ConcurrentHashMapV8<T, Node<T>>(size, loadFactor, 32);
    }

    @Override
    public boolean add(T element) {
        if (element == null) {
            return false;
        }

        // had to modify the super implementation so we publish Node<T> back
        Node<T> alreadyPresent = this.entries.putIfAbsent(element, this.IN_PROGRESS_MARKER);
        if (alreadyPresent == null) {
            // this doesn't already exist
            Node<T> offerNode = super.offerNode(element);

            this.entries.put(element, offerNode);
            return true;
        }

        return false;
    }

    @Override
    public boolean contains(Object element) {
        if (element == null) {
            return false;
        }

        Node<T> node;
        while ((node = this.entries.get(element)) == this.IN_PROGRESS_MARKER) {
            ; // data race
        }

        if (node == null) {
            return false;
        }

        return node.item != null;
    }

    @Override
    public int size() {
        return this.entries.size();
    }

    @Override
    public boolean isEmpty() {
        return super.isEmpty();
    }

    /**
     * @return TRUE if the element was successfully removed
     */
    @Override
    public boolean remove(Object element) {
        while (this.entries.get(element) == this.IN_PROGRESS_MARKER) {
            ; // data race
        }

        Node<T> node = this.entries.remove(element);
        if (node == null) {
            return false;
        }

        Node<T> pred = null;
        for (Node<T> p = this.head; p != null; p = succ(p)) {
            T item = p.item;
            if (item != null &&
                element.equals(item) &&
                p.casItem(item, null)) {
                Node<T> next = succ(p);
                if (pred != null && next != null) {
                    pred.casNext(p, next);
                }
                return true;
            }
            pred = p;
        }
        return false;
    }

    @Override
    public Iterator<T> iterator() {
        return new Itr2();
    }

    private class Itr2 implements Iterator<T> {
        /**
         * Next node to return item for.
         */
        private Node<T> nextNode;

        /**
         * Node of the last returned item, to support remove.
         */
        private Node<T> lastRet;

        /**
         * nextItem holds on to item fields because once we claim
         * that an element exists in hasNext(), we must return it in
         * the following next() call even if it was in the process of
         * being removed when hasNext() was called.
         */
        private T nextItem;

        Itr2() {
            advance();
        }

        /**
         * Moves to next valid node and returns item to return for
         * next(), or null if no such.
         */
        private T advance() {
            this.lastRet = this.nextNode; // for removing items via iterator
            T nextItem = this.nextItem;

            Node<T> pred, p;
            if (this.nextNode == null) {
                p = first();
                pred = null;
            } else {
                pred = this.nextNode;
                p = succ(this.nextNode);
            }

            for (;;) {
                if (p == null) {
                    this.nextNode = null;
                    this.nextItem = null;
                    return nextItem;
                }

                T item = p.item;
                if (item != null) {
                    this.nextNode = p;
                    this.nextItem = item;
                    return nextItem;
                } else {
                    // skip over nulls
                    Node<T> next = succ(p);
                    if (pred != null && next != null) {
                        pred.casNext(p, next);
                    }
                    p = next;
                }
            }
        }



        @Override
        public boolean hasNext() {
            return this.nextNode != null;
        }

        @Override
        public T next() {
            if (this.nextNode == null) {
                throw new NoSuchElementException();
            }
            return advance();
        }

        @Override
        public void remove() {
            Node<T> l = this.lastRet;
            if (l == null) {
                throw new IllegalStateException();
            }

            T value = l.item;

            if (value != null) {
                ConcurrentMap<T, Node<T>> entries2 = ConcurrentSet.this.entries;
                while (entries2.get(value) == ConcurrentSet.this.IN_PROGRESS_MARKER) {
                    ; // data race
                }

                entries2.remove(value);

                // rely on a future traversal to relink.
                l.item = null;
                this.lastRet = null;
            }
        }
    }

    @Override
    public Object[] toArray() {
        return this.entries.keySet().toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return this.entries.keySet().toArray(a);
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
        super.clear();
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
        ConcurrentSet other = (ConcurrentSet) obj;
        if (this.ID != other.ID) {
            return false;
        }
        return true;
    }
}
