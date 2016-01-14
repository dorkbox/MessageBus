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

import com.esotericsoftware.reflectasm.MethodAccess;
import dorkbox.util.messagebus.common.MessageHandler;
import dorkbox.util.messagebus.dispatch.IHandlerInvocation;
import dorkbox.util.messagebus.dispatch.ReflectiveHandlerInvocation;
import dorkbox.util.messagebus.dispatch.SynchronizedHandlerInvocation;

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A subscription is a thread-safe container that manages exactly one message handler of all registered
 * message listeners of the same class, i.e. all subscribed instances (excluding subclasses) of a SingleMessageHandler.class
 * will be referenced in the subscription created for SingleMessageHandler.class.
 * <p/>
 * There will be as many unique subscription objects per message listener class as there are message handlers
 * defined in the message listeners class hierarchy.
 * <p/>
 * The subscription provides functionality for message publication by means of delegation to the respective
 * message dispatcher.
 *
 * @author bennidi
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public final
class Subscription {
    private static final AtomicInteger ID_COUNTER = new AtomicInteger();
    public final int ID = ID_COUNTER.getAndIncrement();


    // the handler's metadata -> for each handler in a listener, a unique subscription context is created
    private final MessageHandler handlerMetadata;

    private final IHandlerInvocation invocation;
    private final Collection<Object> listeners;

    public
    Subscription(final MessageHandler handler, final float loadFactor, final int stripeSize) {
        this.handlerMetadata = handler;
//        this.listeners = new StrongConcurrentSetV8<Object>(16, loadFactor, stripeSize);

        ///this is by far, the fastest
        this.listeners = new ConcurrentSkipListSet<>(new Comparator() {
            @Override
            public
            int compare(final Object o1, final Object o2) {
                return Integer.compare(o1.hashCode(), o2.hashCode());
            }
        });
//        this.listeners = new StrongConcurrentSet<Object>(16, 0.85F);
//        this.listeners = new ConcurrentLinkedQueue2<Object>();
//        this.listeners = new CopyOnWriteArrayList<Object>();
//        this.listeners = new CopyOnWriteArraySet<Object>();  // not very good

        IHandlerInvocation invocation = new ReflectiveHandlerInvocation();
        if (handler.isSynchronized()) {
            invocation = new SynchronizedHandlerInvocation(invocation);
        }

        this.invocation = invocation;
    }

    public
    MessageHandler getHandler() {
        return handlerMetadata;
    }

    public
    boolean isEmpty() {
        return this.listeners.isEmpty();
    }

    public
    void subscribe(Object listener) {
        this.listeners.add(listener);
    }

    /**
     * @return TRUE if the element was removed
     */
    public
    boolean unsubscribe(Object existingListener) {
        return this.listeners.remove(existingListener);
    }

    // only used in unit-test
    public
    int size() {
        return this.listeners.size();
    }

    public
    void publish(final Object message) throws Throwable {
        final MethodAccess handler = this.handlerMetadata.getHandler();
        final int handleIndex = this.handlerMetadata.getMethodIndex();
        final IHandlerInvocation invocation = this.invocation;

        Iterator<Object> iterator;
        Object listener;

        for (iterator = this.listeners.iterator(); iterator.hasNext(); ) {
            listener = iterator.next();

            invocation.invoke(listener, handler, handleIndex, message);
        }
    }

    public
    void publish(final Object message1, final Object message2) throws Throwable {
        final MethodAccess handler = this.handlerMetadata.getHandler();
        final int handleIndex = this.handlerMetadata.getMethodIndex();
        final IHandlerInvocation invocation = this.invocation;

        Iterator<Object> iterator;
        Object listener;

        for (iterator = this.listeners.iterator(); iterator.hasNext(); ) {
            listener = iterator.next();

            invocation.invoke(listener, handler, handleIndex, message1, message2);
        }
    }

    public
    void publish(final Object message1, final Object message2, final Object message3) throws Throwable {
        final MethodAccess handler = this.handlerMetadata.getHandler();
        final int handleIndex = this.handlerMetadata.getMethodIndex();
        final IHandlerInvocation invocation = this.invocation;

        Iterator<Object> iterator;
        Object listener;

        for (iterator = this.listeners.iterator(); iterator.hasNext(); ) {
            listener = iterator.next();

            invocation.invoke(listener, handler, handleIndex, message1, message2, message3);
        }
    }

    public
    void publishToSubscription(final Object... messages) throws Throwable {
        final MethodAccess handler = this.handlerMetadata.getHandler();
        final int handleIndex = this.handlerMetadata.getMethodIndex();
        final IHandlerInvocation invocation = this.invocation;

        Iterator<Object> iterator;
        Object listener;

        for (iterator = this.listeners.iterator(); iterator.hasNext(); ) {
            listener = iterator.next();

            invocation.invoke(listener, handler, handleIndex, messages);
        }
    }


    @Override
    public
    int hashCode() {
        return this.ID;
    }

    @Override
    public
    boolean equals(Object obj) {
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
