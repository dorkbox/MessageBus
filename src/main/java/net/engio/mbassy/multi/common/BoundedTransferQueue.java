/*
 * Copyright 2006-2008 Makoto YUI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Hanson Char - implemented and released to the public domain.
 *     Makoto YUI - imported and fixed bug in take().
 */
package net.engio.mbassy.multi.common;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BoundedTransferQueue<E> extends AbstractQueue<E> implements TransferQueue<E> {

    private final int _maxCapacity;
    private final AtomicInteger _remainingCapacity;
    private final TransferQueue<E> _queue;

    public BoundedTransferQueue(int capacity) {
        if(capacity < 1) {
            throw new IllegalArgumentException();
        }
        this._maxCapacity = capacity;
        this._remainingCapacity = new AtomicInteger(capacity);
        this._queue = new LinkedTransferQueue<E>();
    }

    @Override
    public boolean offer(E e) {
//        if(tryDecrementCapacity()) {
//            return this._queue.offer(e);
//        }
//        return false;

        try {
            if (tryDecrementCapacity()) {
                this._queue.put(e);
            } else {
                this._queue.transfer(e);
                this._remainingCapacity.decrementAndGet();
            }
        } catch (InterruptedException e2) {}
        return true;
    }

    @Override
    public E poll() {
        final E e = this._queue.poll();
        if(e != null) {
            this._remainingCapacity.incrementAndGet();
        }
        return e;
    }

    @Override
    public void put(E e) throws InterruptedException {
        if (tryDecrementCapacity()) {
            this._queue.put(e);
        } else {
            this._queue.transfer(e);
            this._remainingCapacity.decrementAndGet();
        }
    }

    @Override
    public E take() throws InterruptedException {
        E e = this._queue.take();
        this._remainingCapacity.incrementAndGet();
        return e;
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (tryDecrementCapacity()) {
            return this._queue.offer(e, timeout, unit);
        } else {
            final boolean succeed = this._queue.tryTransfer(e, timeout, unit);
            if (succeed) {
                this._remainingCapacity.decrementAndGet();
            }
            return succeed;
        }
    }

    @Override
    public E poll(final long timeout, final TimeUnit unit) throws InterruptedException {
        final E e = this._queue.poll(timeout, unit);
        if (e != null) {
            this._remainingCapacity.incrementAndGet();
        }
        return e;
    }

    private boolean tryDecrementCapacity() {
        int capacity;
        do {
            capacity = this._remainingCapacity.get();
            if (capacity == 0) {
                return false;
            }
        } while(!this._remainingCapacity.compareAndSet(capacity, capacity - 1));
        return true;
    }

    // -------------------------------------------------------
    // delegates everything

    @Override
    public int remainingCapacity() {
        return this._remainingCapacity.get();
    }

    @Override
    public int drainTo(Collection<? super E> c, int maxElements) {
        return this._queue.drainTo(c, maxElements);
    }

    @Override
    public int drainTo(Collection<? super E> c) {
        return this._queue.drainTo(c);
    }

    @Override
    public Iterator<E> iterator() {
        return this._queue.iterator();
    }

    @Override
    public E peek() {
        return this._queue.peek();
    }

    @Override
    public int size() {
        return this._queue.size();
    }

    @Override
    public void clear() {
        this._queue.clear();
        this._remainingCapacity.set(this._maxCapacity);
    }



    @Override
    public boolean tryTransfer(E e) {
        boolean tryTransfer = this._queue.tryTransfer(e);
        if (tryTransfer) {
            this._remainingCapacity.decrementAndGet();
        }
        return tryTransfer;
    }

    @Override
    public void transfer(E e) throws InterruptedException {
        this._queue.transfer(e);
        this._remainingCapacity.decrementAndGet();
    }

    @Override
    public boolean tryTransfer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        boolean tryTransfer = this._queue.tryTransfer(e, timeout, unit);
        return tryTransfer;
    }

    @Override
    public boolean hasWaitingConsumer() {
        return this._queue.hasWaitingConsumer();
    }

    @Override
    public int getWaitingConsumerCount() {
        return this._queue.getWaitingConsumerCount();
    }
}
