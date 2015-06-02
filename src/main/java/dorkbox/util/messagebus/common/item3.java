package dorkbox.util.messagebus.common;

import java.util.Iterator;

abstract class item3<T> implements Iterator<T> {
    public ISetEntry<T> current;

    public item3(ISetEntry<T> current) {
        this.current = current;
    }
}
