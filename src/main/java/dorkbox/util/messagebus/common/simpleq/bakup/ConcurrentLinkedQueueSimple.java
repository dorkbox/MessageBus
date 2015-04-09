/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/licenses/publicdomain
 */

package dorkbox.util.messagebus.common.simpleq.bakup;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;


/**
 * An unbounded thread-safe {@linkplain Queue queue} based on linked nodes.
 * This queue orders elements FIFO (first-in-first-out).
 * The <em>head</em> of the queue is that element that has been on the
 * queue the longest time.
 * The <em>tail</em> of the queue is that element that has been on the
 * queue the shortest time. New elements
 * are inserted at the tail of the queue, and the queue retrieval
 * operations obtain elements at the head of the queue.
 * A <tt>ConcurrentLinkedQueue</tt> is an appropriate choice when
 * many threads will share access to a common collection.
 * This queue does not permit <tt>null</tt> elements.
 *
 * <p>This implementation employs an efficient &quot;wait-free&quot;
 * algorithm based on one described in <a
 * href="http://www.cs.rochester.edu/u/michael/PODC96.html"> Simple,
 * Fast, and Practical Non-Blocking and Blocking Concurrent Queue
 * Algorithms</a> by Maged M. Michael and Michael L. Scott.
 *
 * <p>Beware that, unlike in most collections, the <tt>size</tt> method
 * is <em>NOT</em> a constant-time operation. Because of the
 * asynchronous nature of these queues, determining the current number
 * of elements requires a traversal of the elements.
 *
 * <p>This class and its iterator implement all of the
 * <em>optional</em> methods of the {@link Collection} and {@link
 * Iterator} interfaces.
 *
 * <p>Memory consistency effects: As with other concurrent
 * collections, actions in a thread prior to placing an object into a
 * {@code ConcurrentLinkedQueue}
 * <a href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * actions subsequent to the access or removal of that element from
 * the {@code ConcurrentLinkedQueue} in another thread.
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * @since 1.5
 * @author Doug Lea
 * @param <E> the type of elements held in this collection
 *
 */
public class ConcurrentLinkedQueueSimple<E> extends AbstractQueue<E> implements Queue<E> {
    /*
     * This is a straight adaptation of Michael & Scott algorithm.
     * For explanation, read the paper.  The only (minor) algorithmic
     * difference is that this version supports lazy deletion of
     * internal nodes (method remove(Object)) -- remove CAS'es item
     * fields to null. The normal queue operations unlink but then
     * pass over nodes with null item fields. Similarly, iteration
     * methods ignore those with nulls.
     *
     * Also note that like most non-blocking algorithms in this
     * package, this implementation relies on the fact that in garbage
     * collected systems, there is no possibility of ABA problems due
     * to recycled nodes, so there is no need to use "counted
     * pointers" or related techniques seen in versions used in
     * non-GC'ed settings.
     */
    private static class Node<E> {
        private volatile E item;
        private volatile Node<E> next;

        private static final AtomicReferenceFieldUpdater<Node, Node>
        nextUpdater = AtomicReferenceFieldUpdater.newUpdater(Node.class, Node.class, "next");

        private static final AtomicReferenceFieldUpdater<Node, Object>
        itemUpdater = AtomicReferenceFieldUpdater.newUpdater(Node.class, Object.class, "item");

        Node(E x) { this.item = x; }

        Node(E x, Node<E> n) { this.item = x; this.next = n; }

        E getItem() {
            return this.item;
        }

        boolean casItem(E cmp, E val) {
            return itemUpdater.compareAndSet(this, cmp, val);
        }

        void setItem(E val) {
            itemUpdater.set(this, val);
        }

        Node<E> getNext() {
            return this.next;
        }

        boolean casNext(Node<E> cmp, Node<E> val) {
            return nextUpdater.compareAndSet(this, cmp, val);
        }

        void setNext(Node<E> val) {
            nextUpdater.set(this, val);
        }
    }

    private static final AtomicReferenceFieldUpdater<ConcurrentLinkedQueueSimple, Node>
    tailUpdater = AtomicReferenceFieldUpdater.newUpdater(ConcurrentLinkedQueueSimple.class, Node.class, "tail");

    private static final AtomicReferenceFieldUpdater<ConcurrentLinkedQueueSimple, Node>
    headUpdater = AtomicReferenceFieldUpdater.newUpdater(ConcurrentLinkedQueueSimple.class,  Node.class, "head");

    private boolean casTail(Node<E> cmp, Node<E> val) {
        return tailUpdater.compareAndSet(this, cmp, val);
    }

    private boolean casHead(Node<E> cmp, Node<E> val) {
        return headUpdater.compareAndSet(this, cmp, val);
    }


    /**
     * Pointer to header node, initialized to a dummy node.  The first
     * actual node is at head.getNext().
     */
    private transient volatile Node<E> head = new Node<E>(null, null);

    /** Pointer to last node on list **/
    private transient volatile Node<E> tail = this.head;


    /**
     * Creates a <tt>ConcurrentLinkedQueue</tt> that is initially empty.
     */
    public ConcurrentLinkedQueueSimple() {}

    /**
     * Creates a <tt>ConcurrentLinkedQueue</tt>
     * initially containing the elements of the given collection,
     * added in traversal order of the collection's iterator.
     * @param c the collection of elements to initially contain
     * @throws NullPointerException if the specified collection or any
     *         of its elements are null
     */
    public ConcurrentLinkedQueueSimple(Collection<? extends E> c) {
        for (Iterator<? extends E> it = c.iterator(); it.hasNext();) {
            add(it.next());
        }
    }

    // Have to override just to update the javadoc

    /**
     * Inserts the specified element at the tail of this queue.
     *
     * @return <tt>true</tt> (as specified by {@link Collection#add})
     * @throws NullPointerException if the specified element is null
     */
    @Override
    public boolean add(E e) {
        return offer(e);
    }

    /**
     * Inserts the specified element at the tail of this queue.
     *
     * @return <tt>true</tt> (as specified by {@link Queue#offer})
     * @throws NullPointerException if the specified element is null
     */
    @Override
    public boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException();
        }

        Node<E> n = new Node<E>(e, null);
        for (;;) {
            Node<E> t = this.tail;
            Node<E> s = t.getNext();
            if (t == this.tail) {
                if (s == null) {
                    if (t.casNext(s, n)) {
                        casTail(t, n);
                        return true;
                    }
                } else {
                    casTail(t, s);
                }
            }
        }
    }

    @Override
    public E poll() {
        for (;;) {
            Node<E> h = this.head;
            Node<E> t = this.tail;
            Node<E> first = h.getNext();
            if (h == this.head) {
                if (h == t) {
                    if (first == null) {
                        return null;
                    } else {
                        casTail(t, first);
                    }
                } else if (casHead(h, first)) {
                    E item = first.getItem();
                    if (item != null) {
                        first.setItem(null);
                        return item;
                    }
                    // else skip over deleted item, continue loop,
                }
            }
        }
    }

    @Override
    public E peek() { // same as poll except don't remove item
        for (;;) {
            Node<E> h = this.head;
            Node<E> t = this.tail;
            Node<E> first = h.getNext();
            if (h == this.head) {
                if (h == t) {
                    if (first == null) {
                        return null;
                    } else {
                        casTail(t, first);
                    }
                } else {
                    E item = first.getItem();
                    if (item != null) {
                        return item;
                    } else {
                        casHead(h, first);
                    }
                }
            }
        }
    }

    /**
     * Returns the first actual (non-header) node on list.  This is yet
     * another variant of poll/peek; here returning out the first
     * node, not element (so we cannot collapse with peek() without
     * introducing race.)
     */
    Node<E> first() {
        for (;;) {
            Node<E> h = this.head;
            Node<E> t = this.tail;
            Node<E> first = h.getNext();
            if (h == this.head) {
                if (h == t) {
                    if (first == null) {
                        return null;
                    } else {
                        casTail(t, first);
                    }
                } else {
                    if (first.getItem() != null) {
                        return first;
                    } else {
                        casHead(h, first);
                    }
                }
            }
        }
    }


    /**
     * Returns <tt>true</tt> if this queue contains no elements.
     *
     * @return <tt>true</tt> if this queue contains no elements
     */
    @Override
    public boolean isEmpty() {
        return first() == null;
    }

    /**
     * Returns the number of elements in this queue.  If this queue
     * contains more than <tt>Integer.MAX_VALUE</tt> elements, returns
     * <tt>Integer.MAX_VALUE</tt>.
     *
     * <p>Beware that, unlike in most collections, this method is
     * <em>NOT</em> a constant-time operation. Because of the
     * asynchronous nature of these queues, determining the current
     * number of elements requires an O(n) traversal.
     *
     * @return the number of elements in this queue
     */
    @Override
    public int size() {
        int count = 0;
        for (Node<E> p = first(); p != null; p = p.getNext()) {
            if (p.getItem() != null) {
                // Collections.size() spec says to max out
                if (++count == Integer.MAX_VALUE) {
                    break;
                }
            }
        }
        return count;
    }

    /**
     * Returns <tt>true</tt> if this queue contains the specified element.
     * More formally, returns <tt>true</tt> if and only if this queue contains
     * at least one element <tt>e</tt> such that <tt>o.equals(e)</tt>.
     *
     * @param o object to be checked for containment in this queue
     * @return <tt>true</tt> if this queue contains the specified element
     */
    @Override
    public boolean contains(Object o) {
        if (o == null) {
            return false;
        }
        for (Node<E> p = first(); p != null; p = p.getNext()) {
            E item = p.getItem();
            if (item != null &&
                            o.equals(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes a single instance of the specified element from this queue,
     * if it is present.  More formally, removes an element <tt>e</tt> such
     * that <tt>o.equals(e)</tt>, if this queue contains one or more such
     * elements.
     * Returns <tt>true</tt> if this queue contained the specified element
     * (or equivalently, if this queue changed as a result of the call).
     *
     * @param o element to be removed from this queue, if present
     * @return <tt>true</tt> if this queue changed as a result of the call
     */
    @Override
    public boolean remove(Object o) {
        if (o == null) {
            return false;
        }
        for (Node<E> p = first(); p != null; p = p.getNext()) {
            E item = p.getItem();
            if (item != null &&
                            o.equals(item) &&
                            p.casItem(item, null)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns an iterator over the elements in this queue in proper sequence.
     * The returned iterator is a "weakly consistent" iterator that
     * will never throw {@link ConcurrentModificationException},
     * and guarantees to traverse elements as they existed upon
     * construction of the iterator, and may (but is not guaranteed to)
     * reflect any modifications subsequent to construction.
     *
     * @return an iterator over the elements in this queue in proper sequence
     */
    @Override
    public Iterator<E> iterator() {
        return new Itr();
    }

    private class Itr implements Iterator<E> {
        /**
         * Next node to return item for.
         */
        private Node<E> nextNode;

        /**
         * nextItem holds on to item fields because once we claim
         * that an element exists in hasNext(), we must return it in
         * the following next() call even if it was in the process of
         * being removed when hasNext() was called.
         */
        private E nextItem;

        /**
         * Node of the last returned item, to support remove.
         */
        private Node<E> lastRet;

        Itr() {
            advance();
        }

        /**
         * Moves to next valid node and returns item to return for
         * next(), or null if no such.
         */
        private E advance() {
            this.lastRet = this.nextNode;
            E x = this.nextItem;

            Node<E> p = this.nextNode == null? first() : this.nextNode.getNext();
            for (;;) {
                if (p == null) {
                    this.nextNode = null;
                    this.nextItem = null;
                    return x;
                }
                E item = p.getItem();
                if (item != null) {
                    this.nextNode = p;
                    this.nextItem = item;
                    return x;
                } else {
                    p = p.getNext();
                }
            }
        }

        @Override
        public boolean hasNext() {
            return this.nextNode != null;
        }

        @Override
        public E next() {
            if (this.nextNode == null) {
                throw new NoSuchElementException();
            }
            return advance();
        }

        @Override
        public void remove() {
            Node<E> l = this.lastRet;
            if (l == null) {
                throw new IllegalStateException();
            }
            // rely on a future traversal to relink.
            l.setItem(null);
            this.lastRet = null;
        }
    }
}