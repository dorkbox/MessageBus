/*
 * Copyright 2014 dorkbox, llc
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
 */
package dorkbox.util.messagebus.common.simpleq;

import java.util.concurrent.atomic.AtomicBoolean;

public class ObjectPoolHolder<T> {

    // enough padding for 64bytes with 4byte refs, to alleviate contention across threads CASing one vs the other.
    @SuppressWarnings("unused")
    Object p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, pa, pb, pc, pd, pe;

    private T value;

    AtomicBoolean state = new AtomicBoolean(true);
    transient volatile Thread waiter;       // to control park/unpark


    public ObjectPoolHolder(T value) {
        this.value = value;
    }

    public T getValue() {
        return this.value;
    }
}
