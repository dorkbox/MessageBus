/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.util.messagebus.common.simpleq.jctools;


import static dorkbox.util.messagebus.common.simpleq.jctools.UnsafeAccess.UNSAFE;

import java.util.AbstractQueue;
import java.util.Iterator;

abstract class ConcurrentCircularArrayQueueL0Pad<E> extends AbstractQueue<E> implements MessagePassingQueue<E> {
    long p00, p01, p02, p03, p04, p05, p06, p07;
    long p30, p31, p32, p33, p34, p35, p36, p37;
}

/**
 * A concurrent access enabling class used by circular array based queues this class exposes an offset computation
 * method along with differently memory fenced load/store methods into the underlying array. The class is pre-padded and
 * the array is padded on either side to help with False sharing prevention. It is expected that subclasses handle post
 * padding.
 * <p>
 * Offset calculation is separate from access to enable the reuse of a give compute offset.
 * <p>
 * Load/Store methods using a <i>buffer</i> parameter are provided to allow the prevention of final field reload after a
 * LoadLoad barrier.
 * <p>
 *
 * @author nitsanw
 *
 * @param <E>
 */
public abstract class ConcurrentCircularArrayQueue<E> extends ConcurrentCircularArrayQueueL0Pad<E> {
    protected static final int SPARSE_SHIFT = Integer.getInteger("sparse.shift", 0);
    protected static final int BUFFER_PAD;
    private static final long REF_ARRAY_BASE;
    private static final int REF_ELEMENT_SHIFT;

    static {
        final int scale = UnsafeAccess.UNSAFE.arrayIndexScale(Object[].class);
        if (4 == scale) {
            REF_ELEMENT_SHIFT = 2 + SPARSE_SHIFT;
        } else if (8 == scale) {
            REF_ELEMENT_SHIFT = 3 + SPARSE_SHIFT;
        } else {
            throw new IllegalStateException("Unknown pointer size");
        }

        BUFFER_PAD = 128 / scale;
        // Including the buffer pad in the array base offset
        REF_ARRAY_BASE = UnsafeAccess.UNSAFE.arrayBaseOffset(Object[].class)
                + (BUFFER_PAD << REF_ELEMENT_SHIFT - SPARSE_SHIFT);
    }
    protected final long mask;
    // @Stable :(
    protected final E[] buffer;
    protected final Boolean[] typeBuffer;
    protected final Thread[] threadBuffer;

    @SuppressWarnings("unchecked")
    public ConcurrentCircularArrayQueue(int capacity) {
        int actualCapacity = Pow2.roundToPowerOfTwo(capacity);
        this.mask = actualCapacity - 1;
        // pad data on either end with some empty slots.
        this.buffer = (E[]) new Object[(actualCapacity << SPARSE_SHIFT) + BUFFER_PAD * 2];
        this.typeBuffer = new Boolean[(actualCapacity << SPARSE_SHIFT) + BUFFER_PAD * 2];
        this.threadBuffer = new Thread[(actualCapacity << SPARSE_SHIFT) + BUFFER_PAD * 2];
    }

    /**
     * @param index desirable element index
     * @return the offset in bytes within the array for a given index.
     */
    protected final long calcElementOffset(long index) {
        return calcElementOffset(index, this.mask);
    }
    /**
     * @param index desirable element index
     * @param mask
     * @return the offset in bytes within the array for a given index.
     */
    protected static final long calcElementOffset(long index, long mask) {
        return REF_ARRAY_BASE + ((index & mask) << REF_ELEMENT_SHIFT);
    }

    /**
     * A plain store (no ordering/fences) of an element to a given offset
     *
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @param e a kitty
     */
    protected final void spElement(long offset, E e) {
        UNSAFE.putObject(this.buffer, offset, e);
    }

    /**
     * A plain store (no ordering/fences) of an element TYPE to a given offset
     *
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @param e a kitty
     */
    protected final void spElementType(long offset, int e) {
        UNSAFE.putInt(this.typeBuffer, offset, e);
    }

    /**
     * An ordered store(store + StoreStore barrier) of an element TYPE to a given offset
     *
     * @param buffer this.buffer
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @param e an orderly kitty
     */
    protected final void soElementType(long offset, int e) {
        UNSAFE.putOrderedInt(this.typeBuffer, offset, e);
    }

    /**
     * A plain store (no ordering/fences) of an element THREAD to a given offset
     *
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @param e a kitty
     */
    protected final void spElementThread(long offset, Thread e) {
        UNSAFE.putObject(this.threadBuffer, offset, e);
    }

    /**
     * An ordered store(store + StoreStore barrier) of an element to a given offset
     *
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @param e an orderly kitty
     */
    protected final void soElement(long offset, E e) {
        UNSAFE.putOrderedObject(this.buffer, offset, e);
    }

    /**
     * A plain load (no ordering/fences) of an element from a given offset.
     *
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @return the element at the offset
     */
    @SuppressWarnings("unchecked")
    protected final E lpElement(long offset) {
        return (E) UNSAFE.getObject(this.buffer, offset);
    }

    /**
     * A plain load (no ordering/fences) of an element from a given offset.
     *
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @return the element at the offset
     */
    protected final Object lpElementNoCast(long offset) {
        return UNSAFE.getObject(this.buffer, offset);
    }

    /**
     * A plain load (no ordering/fences) of an element TYPE from a given offset.
     *
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @return the element at the offset
     */
    protected final int lpElementType(long offset) {
        return UNSAFE.getInt(this.typeBuffer, offset);
    }

    /**
     * A volatile load (load + LoadLoad barrier) of an element TYPE from a given offset.
     *
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @return the element at the offset
     */
    protected final int lvElementType(long offset) {
        return UNSAFE.getIntVolatile(this.typeBuffer, offset);
    }

    /**
     * A plain load (no ordering/fences) of an element THREAD from a given offset.
     *
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @return the element at the offset
     */
    protected final Object lpElementThread(long offset) {
        return UNSAFE.getObject(this.threadBuffer, offset);
    }

    /**
     * A volatile load (load + LoadLoad barrier) of an element from a given offset.
     *
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @return the element at the offset
     */
    @SuppressWarnings("unchecked")
    protected final E lvElement(long offset) {
        return (E) UNSAFE.getObjectVolatile(this.buffer, offset);
    }

    /**
     * A volatile load (load + LoadLoad barrier) of an element THREAD from a given offset.
     *
     * @param offset computed via {@link ConcurrentCircularArrayQueue#calcElementOffset(long)}
     * @return the element at the offset
     */
    protected final Object lvElementThread(long offset) {
        return UNSAFE.getObjectVolatile(this.threadBuffer, offset);
    }

    protected final boolean casElementThread(long offset, Object expect, Object newValue) {
        return UNSAFE.compareAndSwapObject(this.threadBuffer, offset, expect, newValue);
    }

    protected final boolean casElementType(long offset, int expect, int newValue) {
        return UNSAFE.compareAndSwapInt(this.typeBuffer, offset, expect, newValue);
    }

    @Override
    public Iterator<E> iterator() {
        throw new UnsupportedOperationException();
    }
    @Override
    public void clear() {
        // we have to test isEmpty because of the weaker poll() guarantee
        while (poll() != null || !isEmpty()) {
            ;
        }
    }
}
