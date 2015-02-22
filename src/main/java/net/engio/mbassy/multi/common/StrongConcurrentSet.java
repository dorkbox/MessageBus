package net.engio.mbassy.multi.common;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This implementation uses strong references to the elements.
 * <p/>
 *
 * @author bennidi
 *         Date: 2/12/12
 */
public class StrongConcurrentSet<T> extends AbstractConcurrentSet<T> {


    public StrongConcurrentSet() {
        this(16, 0.75f);
    }

    public StrongConcurrentSet(int size, float loadFactor) {
        super(new ConcurrentHashMap<T, ISetEntry<T>>(size, loadFactor, 1));
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<T>() {
            private ISetEntry<T> current = StrongConcurrentSet.this.head;

            @Override
            public boolean hasNext() {
                return this.current != null;
            }

            @Override
            public T next() {
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
            public void remove() {
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
    protected Entry<T> createEntry(T value, Entry<T> next) {
        return next != null ? new StrongEntry<T>(value, next) : new StrongEntry<T>(value);
    }


    public static class StrongEntry<T> extends Entry<T> {

        private T value;

        private StrongEntry(T value, Entry<T> next) {
            super(next);
            this.value = value;
        }

        private StrongEntry(T value) {
            super();
            this.value = value;
        }

        @Override
        public T getValue() {
            return this.value;
        }
    }
}