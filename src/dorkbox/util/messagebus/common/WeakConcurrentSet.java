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
 */
package dorkbox.util.messagebus.common;


import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.WeakHashMap;
import java.util.concurrent.locks.StampedLock;

/**
 * This implementation uses weak references to the elements. Iterators automatically perform cleanups of
 * garbage collected objects during iteration -> no dedicated maintenance operations need to be called or run in background.
 *
 * @author bennidi
 *         Date: 2/12/12
 */
public
class WeakConcurrentSet<T> extends AbstractConcurrentSet<T> {


    public
    WeakConcurrentSet() {
        super(new WeakHashMap<T, ISetEntry<T>>());
    }

    @Override
    public
    Iterator<T> iterator() {
        return new Iterator<T>() {

            // the current list element of this iterator
            // used to keep track of the iteration process
            private ISetEntry<T> current = WeakConcurrentSet.this.head;

            // this method will remove all orphaned entries
            // until it finds the first entry whose value has not yet been garbage collected
            // the method assumes that the current element is already orphaned and will remove it
            private
            void removeOrphans() {

                StampedLock lock = WeakConcurrentSet.this.lock;
                long stamp = lock.writeLock();
//                final Lock writeLock = WeakConcurrentSet.this.lock.writeLock();
//                writeLock.lock();
                try {
                    do {
                        ISetEntry<T> orphaned = this.current;
                        this.current = this.current.next();
                        orphaned.remove();
                    } while (this.current != null && this.current.getValue() == null);
                } finally {
                    lock.unlockWrite(stamp);
//                    writeLock.unlock();
                }
            }


            @Override
            public
            boolean hasNext() {
                if (this.current == null) {
                    return false;
                }
                if (this.current.getValue() == null) {
                    // trigger removal of orphan references
                    // because a null value indicates that the value has been garbage collected
                    removeOrphans();
                    return this.current != null; // if any entry is left then it will have a value
                }
                else {
                    return true;
                }
            }

            @Override
            public
            T next() {
                if (this.current == null) {
                    return null;
                }
                T value = this.current.getValue();
                if (value == null) {    // auto-removal of orphan references
                    removeOrphans();
                    return next();
                }
                else {
                    this.current = this.current.next();
                    return value;
                }
            }

            @Override
            public
            void remove() {
                //throw new UnsupportedOperationException("Explicit removal of set elements is only allowed via the controlling set. Sorry!");
                if (this.current == null) {
                    return;
                }
                ISetEntry<T> newCurrent = this.current.next();
                WeakConcurrentSet.this.remove(this.current.getValue());
                this.current = newCurrent;
            }
        };
    }

    @Override
    protected
    Entry<T> createEntry(T value, Entry<T> next) {
        return next != null ? new WeakEntry<T>(value, next) : new WeakEntry<T>(value);
    }


    public static
    class WeakEntry<T> extends Entry<T> {

        private WeakReference<T> value;

        private
        WeakEntry(T value, Entry<T> next) {
            super(next);
            this.value = new WeakReference<T>(value);
        }

        private
        WeakEntry(T value) {
            super();
            this.value = new WeakReference<T>(value);
        }

        @Override
        public
        T getValue() {
            return this.value.get();
        }
    }
}
