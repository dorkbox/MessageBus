/*
 * Copyright 2013 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package dorkbox.util.messagebus.common.simpleq.bakup;

import java.util.concurrent.atomic.AtomicReference;

/**
 * An unbounded thread-safe stack based on linked nodes. This stack orders elements LIFO (last-in-first-out). The <em>top</em> of the stack
 * is that element that has been on the stack the shortest time. New elements are inserted at and retrieved from the top of the stack. A
 * {@code EliminationStack} is an appropriate choice when many threads will exchange elements through shared access to a common collection.
 * Like most other concurrent collection implementations, this class does not permit the use of {@code null} elements.
 * <p>
 * This implementation employs elimination to transfer elements between threads that are pushing and popping concurrently. This technique
 * avoids contention on the stack by attempting to cancel operations if an immediate update to the stack is not successful. This approach is
 * described in <a href="http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.156.8728">A Scalable Lock-free Stack Algorithm</a>.
 * <p>
 * Iterators are <i>weakly consistent</i>, returning elements reflecting the state of the stack at some point at or since the creation of
 * the iterator. They do <em>not</em> throw {@link java.util.ConcurrentModificationException}, and may proceed concurrently with other
 * operations. Elements contained in the stack since the creation of the iterator will be returned exactly once.
 * <p>
 * Beware that, unlike in most collections, the {@code size} method is <em>NOT</em> a constant-time operation. Because of the asynchronous
 * nature of these stacks, determining the current number of elements requires a traversal of the elements, and so may report inaccurate
 * results if this collection is modified during traversal.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @see <a href="https://github.com/ben-manes/caffeine">Caffeine</a>
 * @param <E> the type of elements held in this collection
 */
public final class EliminationStack<E> {

    /*
     * A Treiber's stack is represented as a singly-linked list with an atomic top reference and uses compare-and-swap to modify the value
     * atomically.
     *
     * The stack is augmented with an elimination array to minimize the top reference becoming a sequential bottleneck. Elimination allows
     * pairs of operations with reverse semantics, like pushes and pops on a stack, to complete without any central coordination, and
     * therefore substantially aids scalability [1, 2, 3]. If a thread fails to update the stack's top reference then it backs off to a
     * collision arena where a location is chosen at random and it attempts to coordinate with another operation that concurrently chose the
     * same location. If a transfer is not successful then the thread repeats the process until the element is added to the stack or a
     * cancellation occurs.
     *
     * This implementation borrows optimizations from {@link java.util.concurrent.Exchanger} for choosing an arena location and awaiting a
     * match [4].
     *
     * [1] A Scalable Lock-free Stack Algorithm http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.156.8728 [2] Concurrent Data
     * Structures http://www.cs.tau.ac.il/~shanir/concurrent-data-structures.pdf [3] Using elimination to implement scalable and lock-free
     * fifo queues http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.108.6422 [4] A Scalable Elimination-based Exchange Channel
     * http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.59.7396
     */

    /** The number of CPUs */
    static final int NCPU = Runtime.getRuntime().availableProcessors();

    /** The number of slots in the elimination array. */
    static final int ARENA_LENGTH = ceilingNextPowerOfTwo((NCPU + 1) / 2);

    /** The mask value for indexing into the arena. */
    static int ARENA_MASK = ARENA_LENGTH - 1;

    /** The number of times to step ahead, probe, and try to match. */
    static final int LOOKAHEAD = Math.min(4, NCPU);

    /**
     * The number of times to spin (doing nothing except polling a memory location) before giving up while waiting to eliminate an
     * operation. Should be zero on uniprocessors. On multiprocessors, this value should be large enough so that two threads exchanging
     * items as fast as possible block only when one of them is stalled (due to GC or preemption), but not much longer, to avoid wasting CPU
     * resources. Seen differently, this value is a little over half the number of cycles of an average context switch time on most systems.
     * The value here is approximately the average of those across a range of tested systems.
     */
    static final int SPINS = NCPU == 1 ? 0 : 2000;

    /** The number of times to spin per lookahead step */
    static final int SPINS_PER_STEP = SPINS / LOOKAHEAD;

    /** A marker indicating that the arena slot is free. */
    static final Object FREE = null;

    /** A marker indicating that a thread is waiting in that slot to be transfered an element. */
    static final Object CONSUMER_WAITER = new Object();

    /** A marker indicating that a thread is waiting in that slot to be transfered an element. */
    static final Object WAITER = new Object();

    static int ceilingNextPowerOfTwo(int x) {
        // From Hacker's Delight, Chapter 3, Harry S. Warren Jr.
        return 1 << Integer.SIZE - Integer.numberOfLeadingZeros(x - 1);
    }

    /** The top of the stack. */
    final AtomicReference<Node<E>> top;

    /** The arena where slots can be used to perform an exchange. */
    final AtomicReference<Object>[] arena;

    /** Creates a {@code EliminationStack} that is initially empty. */
    @SuppressWarnings("unchecked")
    public EliminationStack() {
        this.top = new AtomicReference<Node<E>>();
        this.arena = new AtomicReference[ARENA_LENGTH];
        for (int i = 0; i < ARENA_LENGTH; i++) {
            this.arena[i] = new AtomicReference<Object>();
        }
    }

    /**
     * Returns <tt>true</tt> if this stack contains no elements.
     *
     * @return <tt>true</tt> if this stack contains no elements
     */
    public boolean hasPendingMessages() {
        for (;;) {
            Node<E> node = this.top.get();
            if (node == null) {
                return false;
            }
            E e = node.get();
            if (e == null) {
                this.top.compareAndSet(node, node.next);
            } else {
                return true;
            }
        }
    }

    /**
     * Removes and returns the top element or returns <tt>null</tt> if this stack is empty.
     *
     * @return the top of this stack, or <tt>null</tt> if this stack is empty
     */
    public E pop() {
        for (;;) {
            Node<E> current = this.top.get();
            if (current == null) {
                return null;
            }

            // Attempt to pop from the stack, backing off to the elimination array if contended
            if (this.top.get() == current && this.top.compareAndSet(current, current.next)) {
                return current.get();
            }

            E e = tryReceive();
            if (e != null) {
                return e;
            }
        }
    }

    /**
     * Pushes an element onto the stack (in other words, adds an element at the top of this stack).
     *
     * @param e the element to push
     */
    public void push(E e) {
        Node<E> node = new Node<E>(e);
        for (;;) {
            node.next = this.top.get();

            // Attempt to push to the stack, backing off to the elimination array if contended
            if (this.top.get() == node.next && this.top.compareAndSet(node.next, node)) {
                return;
            }
            if (tryTransfer(e)) {
                return;
            }
        }
    }


    public boolean remove(Object o) {
        for (Node<E> node = this.top.get(); node != null; node = node.next) {
            E value = node.get();
            if (o.equals(value) && node.compareAndSet(value, null)) {
                return true;
            }
        }
        return false;
    }


    /**
     * Attempts to transfer the element to a waiting consumer.
     *
     * @param e the element to try to exchange
     * @return if the element was successfully transfered
     */
    boolean tryTransfer(E e) {
        int start = startIndex();
        return scanAndTransferToWaiter(e, start) || awaitExchange(e, start);
    }

    /**
     * Scans the arena searching for a waiting consumer to exchange with.
     *
     * @param e the element to try to exchange
     * @return if the element was successfully transfered
     */
    boolean scanAndTransferToWaiter(E e, int start) {
        for (int i = 0; i < ARENA_LENGTH; i++) {
            int index = start + i & ARENA_MASK;
            AtomicReference<Object> slot = this.arena[index];
            // if some thread is waiting to receive an element then attempt to provide it
            if (slot.get() == WAITER && slot.compareAndSet(WAITER, e)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Waits for (by spinning) to have the element transfered to another thread. The element is filled into an empty slot in the arena and
     * spun on until it is transfered or a per-slot spin limit is reached. This search and wait strategy is repeated by selecting another
     * slot until a total spin limit is reached.
     *
     * @param e the element to transfer
     * @param start the arena location to start at
     * @return if an exchange was completed successfully
     */
    boolean awaitExchange(E e, int start) {
        for (int step = 0, totalSpins = 0; step < ARENA_LENGTH && totalSpins < SPINS; step++) {
            int index = start + step & ARENA_MASK;
            AtomicReference<Object> slot = this.arena[index];

            Object found = slot.get();
            if (found == WAITER && slot.compareAndSet(WAITER, e)) {
                return true;
            } else if (found == FREE && slot.compareAndSet(FREE, e)) {
                int slotSpins = 0;
                for (;;) {
                    found = slot.get();
                    if (found != e) {
                        return true;
                    } else if (slotSpins >= SPINS_PER_STEP && slot.compareAndSet(e, FREE)) {
                        // failed to transfer the element; try a new slot
                        totalSpins += slotSpins;
                        break;
                    }
                    slotSpins++;
                }
            }
        }
        // failed to transfer the element; give up
        return false;
    }

    /**
     * Attempts to receive an element from a waiting provider.
     *
     * @return an element if successfully transfered or null if unsuccessful
     */
    E tryReceive() {
        int start = startIndex();
        E e = scanAndMatch(start);
        return e == null ? awaitMatch(start) : e;
    }

    /**
     * Scans the arena searching for a waiting producer to transfer from.
     *
     * @param start the arena location to start at
     * @return an element if successfully transfered or null if unsuccessful
     */
    E scanAndMatch(int start) {
        for (int i = 0; i < ARENA_LENGTH; i++) {
            int index = start + i & ARENA_MASK;
            AtomicReference<Object> slot = this.arena[index];

            // accept a transfer if an element is available
            Object found = slot.get();
            if (found != FREE && found != WAITER && slot.compareAndSet(found, FREE)) {
                @SuppressWarnings("unchecked")
                E e = (E) found;
                return e;
            }
        }
        return null;
    }

    /**
     * Waits for (by spinning) to have an element transfered from another thread. A marker is filled into an empty slot in the arena and
     * spun on until it is replaced with an element or a per-slot spin limit is reached. This search and wait strategy is repeated by
     * selecting another slot until a total spin limit is reached.
     *
     * @param start the arena location to start at
     * @return an element if successfully transfered or null if unsuccessful
     */
    E awaitMatch(int start) {
        for (int step = 0, totalSpins = 0; step < ARENA_LENGTH && totalSpins < SPINS; step++) {
            int index = start + step & ARENA_MASK;
            AtomicReference<Object> slot = this.arena[index];
            Object found = slot.get();

            if (found == FREE) {
                if (slot.compareAndSet(FREE, WAITER)) {
                    int slotSpins = 0;
                    for (;;) {
                        found = slot.get();
                        if (found != WAITER && slot.compareAndSet(found, FREE)) {
                            @SuppressWarnings("unchecked")
                            E e = (E) found;
                            return e;
                        } else if (slotSpins >= SPINS_PER_STEP && found == WAITER && slot.compareAndSet(WAITER, FREE)) {
                            // failed to receive an element; try a new slot
                            totalSpins += slotSpins;
                            break;
                        }
                        slotSpins++;
                    }
                }
            } else if (found != WAITER && slot.compareAndSet(found, FREE)) {
                @SuppressWarnings("unchecked")
                E e = (E) found;
                return e;
            }
        }

        // failed to receive an element; give up
        return null;
    }

    /**
     * Returns the start index to begin searching the arena with. Uses a one-step FNV-1a hash code
     * (http://www.isthe.com/chongo/tech/comp/fnv/) based on the current thread's Thread.getId(). These hash codes have more uniform
     * distribution properties with respect to small moduli (here 1-31) than do other simple hashing functions. This technique is a
     * simplified version borrowed from {@link java.util.concurrent.Exchanger}'s hashIndex function.
     */
    static int startIndex() {
        long id = Thread.currentThread().getId();
        return ((int) (id ^ id >>> 32) ^ 0x811c9dc5) * 0x01000193;
    }

    /**
     * An item on the stack. The node is mutable prior to being inserted to avoid object churn and is immutable by the time it has been
     * published to other threads.
     */
    static final class Node<E> extends AtomicReference<E> {
        private static final long serialVersionUID = 1L;

        Node<E> next;

        Node(E value) {
            super(value);
        }
    }
}
