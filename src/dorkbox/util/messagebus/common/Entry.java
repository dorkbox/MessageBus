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

// not thread-safe! must be synchronized in enclosing context
public class Entry {
    private final Object value;
    private Entry next;
    private Entry prev;

    public Entry(Object value, Entry next) {
        if (next != null) {
            this.next = next;
            next.prev = this;
        }

        this.value = value;
    }

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

    public Entry next() {
        return this.next;
    }

    public void clear() {
        this.next = null;
    }

    public
    Object getValue() {
        return value;
    }
}
