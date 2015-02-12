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

import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * The atomic reference base padded at the front.
 * Based on Netty's implementation.
 */
abstract class PaddedAtomicLongBase extends FrontPadding {

    private static final long serialVersionUID = 6513142711280243198L;

    private static final AtomicLongFieldUpdater<PaddedAtomicLongBase> updater;

    static {
        updater = AtomicLongFieldUpdater.newUpdater(PaddedAtomicLongBase.class, "value");
    }

    private volatile long value; // 8-byte object field (or 4-byte + padding)

    public final long get() {
        return this.value;
    }

    public final void set(long referent) {
        this.value = referent;
    }

    public final void lazySet(long referent) {
        updater.lazySet(this, referent);
    }

    public final boolean compareAndSet(long expect, long update) {
        return updater.compareAndSet(this, expect, update);
    }

    public final boolean weakCompareAndSet(long expect, long update) {
        return updater.weakCompareAndSet(this, expect, update);
    }

    public final long getAndSet(long newValue) {
        return updater.getAndSet(this, this.value);
    }

    public final long getAndAdd(long delta) {
    	return updater.getAndAdd(this, delta);
    }
    public final long incrementAndGet() {
    	return updater.incrementAndGet(this);
    }
    public final long decrementAndGet() {
    	return updater.decrementAndGet(this);
    }
    public final long getAndIncrement() {
    	return updater.getAndIncrement(this);
    }
    public final long getAndDecrement() {
    	return updater.getAndDecrement(this);
    }
    public final long addAndGet(long delta) {
    	return updater.addAndGet(this, delta);
    }

    @Override
    public String toString() {
        return String.valueOf(get());
    }
}
