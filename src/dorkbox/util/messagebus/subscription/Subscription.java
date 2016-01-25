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
package dorkbox.util.messagebus.subscription;

import com.esotericsoftware.kryo.util.IdentityMap;
import com.esotericsoftware.reflectasm.MethodAccess;
import dorkbox.util.messagebus.common.MessageHandler;
import dorkbox.util.messagebus.dispatch.IHandlerInvocation;
import dorkbox.util.messagebus.dispatch.ReflectiveHandlerInvocation;
import dorkbox.util.messagebus.dispatch.SynchronizedHandlerInvocation;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * A subscription is a container that manages exactly one message handler of all registered
 * message listeners of the same class, i.e. all subscribed instances (excluding subclasses) of a message
 * will be referenced in the subscription created for a message.
 * <p/>
 * There will be as many unique subscription objects per message listener class as there are message handlers
 * defined in the message listeners class hierarchy.
 * <p/>
 * This class uses the "single writer principle", so that the subscription are only MODIFIED by a single thread,
 * but are READ by X number of threads (in a safe way). This uses object thread visibility/publication to work.
 *
 * @author bennidi
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public final
class Subscription {
    private static final int GROW_SIZE = 8;

    private static final AtomicInteger ID_COUNTER = new AtomicInteger();
    public final int ID = ID_COUNTER.getAndIncrement();


    // this is the listener class that created this subscription
    private final Class<?> listenerClass;

    // the handler's metadata -> for each handler in a listener, a unique subscription context is created
    private final MessageHandler handler;
    private final IHandlerInvocation invocation;

    // NOTE: this is still inside the single-writer! can use the same techniques as subscription manager (for thread safe publication)
    private int firstFreeSpot = 0; // only touched by a single thread
    private volatile Object[] listeners = new Object[GROW_SIZE];  // only modified by a single thread

    private final IdentityMap<Object, Integer> listenerMap = new IdentityMap<>(GROW_SIZE);

    // Recommended for best performance while adhering to the "single writer principle". Must be static-final
    private static final AtomicReferenceFieldUpdater<Subscription, Object[]> listenersREF =
                    AtomicReferenceFieldUpdater.newUpdater(Subscription.class,
                                                           Object[].class,
                                                           "listeners");


    public
    Subscription(final Class<?> listenerClass, final MessageHandler handler) {
        this.listenerClass = listenerClass;
        this.handler = handler;

        IHandlerInvocation invocation = new ReflectiveHandlerInvocation();
        if (handler.isSynchronized()) {
            invocation = new SynchronizedHandlerInvocation(invocation);
        }

        this.invocation = invocation;
    }

    // only used in unit tests to verify that the subscription manager is working correctly
    public
    Class<?> getListenerClass() {
        return listenerClass;
    }

    public
    MessageHandler getHandler() {
        return handler;
    }

    public
    void subscribe(final Object listener) {
        // single writer principle!

        Object[] localListeners = listenersREF.get(this);

        final int length = localListeners.length;
        int spotToPlace = firstFreeSpot;

        while (true) {
            if (spotToPlace >= length) {
                // if we couldn't find a place to put the listener, grow the array, but it is never shrunk
                localListeners = Arrays.copyOf(localListeners, length + GROW_SIZE, Object[].class);
                break;
            }

            if (localListeners[spotToPlace] == null) {
                break;
            }
            spotToPlace++;
        }

        listenerMap.put(listener, spotToPlace);
        localListeners[spotToPlace] = listener;

        // mark this spot as taken, so the next subscribe starts out a little ahead
        firstFreeSpot = spotToPlace + 1;
        listenersREF.lazySet(this, localListeners);
    }

    /**
     * @return TRUE if the element was removed
     */
    public
    boolean unsubscribe(final Object listener) {
        // single writer principle!

        final Integer integer = listenerMap.remove(listener);
        Object[] localListeners = listenersREF.get(this);

        if (integer != null) {
            final int index = integer;
            firstFreeSpot = index;
            localListeners[index] = null;
            listenersREF.lazySet(this, localListeners);
            return true;
        }
        else {
            for (int i = 0; i < localListeners.length; i++) {
                if (localListeners[i] == listener) {
                    firstFreeSpot = i;
                    localListeners[i] = null;
                    listenersREF.lazySet(this, localListeners);
                    return true;
                }
            }
        }

        firstFreeSpot = 0;
        return false;
    }

    /**
     * only used in unit tests
     */
    public
    int size() {
        // since this is ONLY used in unit tests, we count how many are non-null

        int count = 0;
        for (int i = 0; i < listeners.length; i++) {
            if (listeners[i] != null) {
                count++;
            }
        }

        return count;
    }

    public
    void publish(final Object message) throws Throwable {
        final MethodAccess handler = this.handler.getHandler();
        final int handleIndex = this.handler.getMethodIndex();
        final IHandlerInvocation invocation = this.invocation;

        Object listener;
        final Object[] localListeners = listenersREF.get(this);
        for (int i = 0; i < localListeners.length; i++) {
            listener = localListeners[i];
            if (listener != null) {
                invocation.invoke(listener, handler, handleIndex, message);
            }
        }
    }

    public
    void publish(final Object message1, final Object message2) throws Throwable {
//        final MethodAccess handler = this.handler.getHandler();
//        final int handleIndex = this.handler.getMethodIndex();
//        final IHandlerInvocation invocation = this.invocation;
//
//        Iterator<Object> iterator;
//        Object listener;
//
//        for (iterator = this.listeners.iterator(); iterator.hasNext(); ) {
//            listener = iterator.next();
//
//            invocation.invoke(listener, handler, handleIndex, message1, message2);
//        }
    }

    public
    void publish(final Object message1, final Object message2, final Object message3) throws Throwable {
//        final MethodAccess handler = this.handler.getHandler();
//        final int handleIndex = this.handler.getMethodIndex();
//        final IHandlerInvocation invocation = this.invocation;
//
//        Iterator<Object> iterator;
//        Object listener;
//
//        for (iterator = this.listeners.iterator(); iterator.hasNext(); ) {
//            listener = iterator.next();
//
//            invocation.invoke(listener, handler, handleIndex, message1, message2, message3);
//        }
    }

    public
    void publish(final Object... messages) throws Throwable {
//        final MethodAccess handler = this.handler.getHandler();
//        final int handleIndex = this.handler.getMethodIndex();
//        final IHandlerInvocation invocation = this.invocation;
//
//        Iterator<Object> iterator;
//        Object listener;
//
//        for (iterator = this.listeners.iterator(); iterator.hasNext(); ) {
//            listener = iterator.next();
//
//            invocation.invoke(listener, handler, handleIndex, messages);
//        }
    }


    @Override
    public
    int hashCode() {
        return this.ID;
    }

    @Override
    public
    boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        Subscription other = (Subscription) obj;
        return this.ID == other.ID;
    }
}
