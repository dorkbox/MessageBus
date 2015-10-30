/*
 * Copyright 2012 Benjamin Diedrichsen
 *
 * Permission is hereby granted, free  of charge, to any person obtaining
 * a  copy  of this  software  and  associated  documentation files  (the
 * "Software"), to  deal in  the Software without  restriction, including
 * without limitation  the rights to  use, copy, modify,  merge, publish,
 * distribute,  sublicense, and/or sell  copies of  the Software,  and to
 * permit persons to whom the Software  is furnished to do so, subject to
 * the following conditions:
 *
 * The  above  copyright  notice  and  this permission  notice  shall  be
 * included in all copies or substantial portions of the Software.
 *
 * THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
 * EXPRESS OR  IMPLIED, INCLUDING  BUT NOT LIMITED  TO THE  WARRANTIES OF
 * MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE,  ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 *
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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * This implementation uses strong references to the elements.
 * <p/>
 *
 * @author bennidi
 *         Date: 2/12/12
 */
public
class StrongConcurrentSet<T> extends AbstractConcurrentSet<T> {


    public
    StrongConcurrentSet() {
        this(16, 0.75f);
    }

    public
    StrongConcurrentSet(int size, float loadFactor) {
        this(new HashMap<T, ISetEntry<T>>(size, loadFactor));
    }

    public
    StrongConcurrentSet(Map<T, ISetEntry<T>> entries) {
        super(entries);
    }

    @Override
    public
    Iterator<T> iterator() {
        return new Iterator<T>() {
            private ISetEntry<T> current = StrongConcurrentSet.this.head;

            @Override
            public
            boolean hasNext() {
                return this.current != null;
            }

            @Override
            public
            T next() {
                if (this.current == null) {
                    return null;
                }
                else {
                    T value = this.current.getValue();
                    this.current = this.current.next();
                    return value;
                }
            }

            @Override
            public
            void remove() {
                if (this.current == null) {
                    return;
                }
                ISetEntry<T> newCurrent = this.current.next();
                StrongConcurrentSet.this.remove(this.current.getValue());
                this.current = newCurrent;
            }
        };
    }

    @Override
    protected
    Entry<T> createEntry(T value, Entry<T> next) {
        return next != null ? new StrongEntry<T>(value, next) : new StrongEntry<T>(value);
    }


    public static
    class StrongEntry<T> extends Entry<T> {

        private T value;

        private
        StrongEntry(T value, Entry<T> next) {
            super(next);
            this.value = value;
        }

        private
        StrongEntry(T value) {
            super();
            this.value = value;
        }

        @Override
        public
        T getValue() {
            return this.value;
        }
    }
}
