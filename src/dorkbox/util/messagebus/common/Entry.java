/*
 * Copyright 2015 dorkbox, llc
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
package dorkbox.util.messagebus.common;

abstract class pad0<T> {
    volatile long z0, z1, z2, z4, z5, z6 = 7L;
}

abstract class item1<T> extends pad0<T>{
    Entry<T> next;
}

abstract class pad1<T> extends item1<T> {
    volatile long z0, z1, z2, z4, z5, z6 = 7L;
}

abstract class item2<T> extends pad1<T> {
    Entry<T> prev;
}

abstract class pad2<T> extends item2<T> {
    volatile long z0, z1, z2, z4, z5, z6 = 7L;
}


public abstract class Entry<T> extends pad2<T> implements ISetEntry<T> {
    protected Entry(Entry<T> next) {
        this.next = next;
        next.prev = this;
    }

    protected Entry() {
    }

    // not thread-safe! must be synchronized in enclosing context
    @Override
    public void remove() {
        if (this.prev != null) {
            this.prev.next = this.next;
            if (this.next != null) {
                this.next.prev = this.prev;
            }
        } else if (this.next != null) {
            this.next.prev = null;
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
