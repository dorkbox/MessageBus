package net.engio.mbassy.multi.common;

import java.util.Collection;

/**
 * Todo: Add javadoc
 *
 * @author bennidi
 *         Date: 3/29/13
 */
public interface IConcurrentSet<T> extends Collection<T> {

    @Override
    boolean add(T element);

    boolean contains(Object element);

    @Override
    int size();

    @Override
    boolean isEmpty();

    void addAll(Iterable<T> elements);

    /**
     * @return TRUE if the element was removed
     */
    boolean remove(Object element);
}
