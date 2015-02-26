package dorkbox.util.messagebus.common;

import java.util.Collection;
import java.util.Iterator;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

public class SuperClassIterator implements Iterator<Class<?>> {
    private final Iterator<Class<?>> iterator;
    private Class<?> clazz;

    public SuperClassIterator(Class<?> clazz, Collection<Class<?>> types) {
        this.clazz = clazz;
        if (types != null) {
            this.iterator = types.iterator();
        } else {
            this.iterator = null;
        }
    }

    @Override
    public boolean hasNext() {
        if (this.clazz != null) {
            return true;
        }

        if (this.iterator != null) {
            return this.iterator.hasNext();
        }

        return false;
    }

    @Override
    public Class<?> next() {
        if (this.clazz != null) {
            Class<?> clazz2 = this.clazz;
            this.clazz = null;

            return clazz2;
        }

        if (this.iterator != null) {
            return this.iterator.next();
        }

        return null;
    }

    @Override
    public void remove() {
        throw new NotImplementedException();
    }
}
