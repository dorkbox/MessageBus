/*
 * Copyright 2014 David Karnok
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package net.engio.mbassy.multi.common;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A bounded MPMC queue implementation based on
 * https://blogs.oracle.com/dave/entry/ptlqueue_a_scalable_bounded_capacity
 * <p>
 * Does not support null values.
 */
public final class PTLQueue<E> {
    private final int mask;
    private final int length;

    private final PaddedAtomicLong offerCursor;
    private final PaddedAtomicLong pollCursor;
    private final AtomicLongArray turns;
    private final AtomicReferenceArray<E> values;

    public PTLQueue(int capacity) {
        capacity = Pow2.roundToPowerOfTwo(capacity);
        this.mask = capacity - 1;
        this.length = capacity;
        this.offerCursor = new PaddedAtomicLong();
        this.pollCursor = new PaddedAtomicLong();
        this.turns = new AtomicLongArray(capacity);
        this.values = new AtomicReferenceArray<>(capacity);
        for  (int i = 0; i < this.length - 1; i++) {
            this.turns.lazySet(i, i);
        }
        this.turns.set(this.length - 1, this.length - 1);
    }

    public void put(E value, Runnable ifWait) {
        nullCheck(value);
        long ticket = this.offerCursor.getAndIncrement();
        int slot = (int)ticket & this.mask;
        while (this.turns.get(slot) != ticket) {
            ifWait.run();
        }
        this.values.set(slot, value);
    }

    public boolean offer(E value) {
        nullCheck(value);
        for (;;) {
            long ticket = this.offerCursor.get();
            int slot = (int)ticket & this.mask;
            if (this.turns.get(slot) != ticket) {
                return false;
            }
            if (this.offerCursor.compareAndSet(ticket, ticket + 1)) {
                this.values.set(slot, value);
                return true;
            }
        }
    }

    private void nullCheck(E value) {
        if (value == null) {
            throw new NullPointerException("Null values not allowed here!");
        }
    }

    public E take(Runnable ifWait) {
        long ticket = this.pollCursor.getAndIncrement();
        int slot = (int)ticket & this.mask;
        while (this.turns.get(slot) != ticket) {
            ifWait.run();
        }
        for (;;) {
            E v = this.values.get(slot);
            if (v != null) {
                this.values.lazySet(slot, null);
                this.turns.set(slot, ticket /* + mask + 1*/);
                return v;
            }
            ifWait.run();
        }
    }

    public boolean isEmpty() {
        for (;;) {
            long ticket = this.pollCursor.get();
            int slot = (int)ticket & this.mask;
            if (this.turns.get(slot) != ticket) {
                return true;
            }
            E v = this.values.get(slot);
            if (v == null) {
                return true;
            }

            return false;
        }
    }

    public E poll() {
        for (;;) {
            long ticket = this.pollCursor.get();
            int slot = (int)ticket & this.mask;
            if (this.turns.get(slot) != ticket) {
                return null;
            }
            E v = this.values.get(slot);
            if (v == null) {
                return null;
            }
            if (this.pollCursor.compareAndSet(ticket, ticket + 1)) {
                this.values.lazySet(slot, null);
                this.turns.set(slot, ticket + this.length);
                return v;
            }
        }
    }

    public E pollStrong() {
        for (;;) {
            long ticket = this.pollCursor.get();
            int slot = (int)ticket & this.mask;
            if (this.turns.get(slot) != ticket) {
                if (this.pollCursor.get() != ticket) {
                    continue;
                }
                return null;
            }
            E v = this.values.get(slot);
            if (v == null) {
                if ((this.pollCursor.get() ^ ticket | this.turns.get(slot) ^ ticket) != 0) {
                    continue;
                }
                return null;
            }
            if (this.pollCursor.compareAndSet(ticket, ticket + 1)) {
                this.values.lazySet(slot, null);
                this.turns.set(slot, ticket + this.length);
                return v;
            }
        }
    }
}
