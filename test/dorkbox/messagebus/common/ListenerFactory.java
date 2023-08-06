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
package dorkbox.messagebus.common;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.fail;

/**
 * The factory can be used to declaratively specify how many instances of some given classes
 * should be created. It will create those instances using reflection and provide a list containing those instances.
 * The factory also holds strong references to the instances such that GC will not interfere with tests unless the
 * factory is explicitly cleared.
 *
 * @author bennidi
 *         Date: 11/22/12
 */
@SuppressWarnings("ALL")
public
class ListenerFactory {

    private Map<Class, Integer> requiredBeans = new HashMap<Class, Integer>();
    private volatile List generatedListeners;
    private int requiredSize = 0;

    public
    int getNumberOfListeners(Class listener) {
        return requiredBeans.containsKey(listener) ? requiredBeans.get(listener) : 0;
    }

    public
    ListenerFactory create(int numberOfInstances, Class clazz) {
        requiredBeans.put(clazz, numberOfInstances);
        requiredSize += numberOfInstances;
        return this;
    }

    public
    ListenerFactory create(int numberOfInstances, Class... classes) {
        for (Class clazz : classes) {
            create(numberOfInstances, clazz);
        }
        return this;
    }

    public
    ListenerFactory create(int numberOfInstances, Collection<Class> classes) {
        for (Class clazz : classes) {
            create(numberOfInstances, clazz);
        }
        return this;
    }


    @SuppressWarnings("unchecked")
    public synchronized
    List<Object> getAll() {
        if (generatedListeners != null) {
            return generatedListeners;
        }
        List listeners = new ArrayList(requiredSize);
        try {
            for (Class clazz : requiredBeans.keySet()) {
                int numberOfRequiredBeans = requiredBeans.get(clazz);
                for (int i = 0; i < numberOfRequiredBeans; i++) {
                    listeners.add(clazz.newInstance());
                }
            }
        } catch (Exception e) {
            // if instantiation fails, counts will be incorrect
            // -> fail early here
            fail("There was a problem instantiating a listener " + e);
        }
        Collections.shuffle(listeners);
        generatedListeners = Collections.unmodifiableList(listeners);
        return generatedListeners;
    }

    // not thread-safe but not yet used concurrently
    public synchronized
    void clear() {
        generatedListeners = null;
        requiredBeans.clear();
    }

    /**
     * Create a thread-safe read-only iterator
     * <p/>
     * NOTE: Iterator is not perfectly synchronized with mutator methods of the list of generated listeners
     * In theory, it is possible that the list is changed while iterators are still running which should be avoided.
     *
     * @return
     */
    public
    Iterator iterator() {
        getAll();
        final AtomicInteger current = new AtomicInteger(0);

        return new Iterator() {
            @Override
            public
            boolean hasNext() {
                return current.get() < generatedListeners.size();
            }

            @Override
            public
            Object next() {
                int index = current.getAndIncrement();
                return index < generatedListeners.size() ? generatedListeners.get(index) : null;
            }

            @Override
            public
            void remove() {
                throw new UnsupportedOperationException("Iterator is read only");
            }
        };
    }


}
