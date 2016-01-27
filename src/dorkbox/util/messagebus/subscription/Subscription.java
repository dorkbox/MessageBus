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
import dorkbox.util.messagebus.common.Entry;
import dorkbox.util.messagebus.common.MessageHandler;
import dorkbox.util.messagebus.dispatch.IHandlerInvocation;
import dorkbox.util.messagebus.dispatch.ReflectiveHandlerInvocation;
import dorkbox.util.messagebus.dispatch.SynchronizedHandlerInvocation;

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
    private static final AtomicInteger ID_COUNTER = new AtomicInteger();
    public final int ID = ID_COUNTER.getAndIncrement();


    // this is the listener class that created this subscription
    private final Class<?> listenerClass;

    // the handler's metadata -> for each handler in a listener, a unique subscription context is created
    private final MessageHandler handler;
    private final IHandlerInvocation invocation;

    // Recommended for best performance while adhering to the "single writer principle". Must be static-final
    private static final AtomicReferenceFieldUpdater<Subscription, Entry> headREF =
                    AtomicReferenceFieldUpdater.newUpdater(Subscription.class,
                                                           Entry.class,
                                                           "head");


    // This is only touched by a single thread!
    private final IdentityMap<Object, Entry> entries; // maintain a map of entries for FAST lookup during unsubscribe.

    // this is still inside the single-writer, and can use the same techniques as subscription manager (for thread safe publication)
    public volatile Entry head; // reference to the first element


    public
    Subscription(final Class<?> listenerClass, final MessageHandler handler) {
        this.listenerClass = listenerClass;
        this.handler = handler;

        IHandlerInvocation invocation = new ReflectiveHandlerInvocation();
        if (handler.isSynchronized()) {
            invocation = new SynchronizedHandlerInvocation(invocation);
        }

        this.invocation = invocation;

        entries = new IdentityMap<>(32, SubscriptionManager.LOAD_FACTOR);


        if (handler.acceptsSubtypes()) {
            // keep a list of "super-class" messages that access this. This is updated by multiple threads. This is so we know WHAT
            // super-subscriptions to clear when we sub/unsub

        }
    }

    // called on shutdown for GC purposes
    public void clear() {
        this.entries.clear();
        this.head.clear();
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
        Entry head = headREF.get(this);

        if (!entries.containsKey(listener)) {
            head = new Entry(listener, head);

            entries.put(listener, head);
            headREF.lazySet(this, head);
        }
    }

    /**
     * @return TRUE if the element was removed
     */
    public
    boolean unsubscribe(final Object listener) {
        Entry entry = entries.get(listener);
        if (entry == null || entry.getValue() == null) {
            // fast exit
            return false;
        }
        else {
            // single writer principle!
            Entry head = headREF.get(this);

            if (entry != head) {
                entry.remove();
            }
            else {
                // if it was second, now it's first
                head = head.next();
                //oldHead.clear(); // optimize for GC not possible because of potentially running iterators
            }

            this.entries.remove(listener);
            headREF.lazySet(this, head);
            return true;
        }
    }

    /**
     * only used in unit tests
     */
    public
    int size() {
        return this.entries.size;
    }

    public
    void publish(final Object message) throws Throwable {
        final MethodAccess handler = this.handler.getHandler();
        final int handleIndex = this.handler.getMethodIndex();
        final IHandlerInvocation invocation = this.invocation;

        Entry current = headREF.get(this);
        Object listener;
        while (current != null) {
            listener = current.getValue();
            current = current.next();

            invocation.invoke(listener, handler, handleIndex, message);
        }
    }

    public
    void publish(final Object message1, final Object message2) throws Throwable {
        final MethodAccess handler = this.handler.getHandler();
        final int handleIndex = this.handler.getMethodIndex();
        final IHandlerInvocation invocation = this.invocation;

        Entry current = headREF.get(this);
        Object listener;
        while (current != null) {
            listener = current.getValue();
            current = current.next();

            invocation.invoke(listener, handler, handleIndex, message1, message2);
        }
    }

    public
    void publish(final Object message1, final Object message2, final Object message3) throws Throwable {
        final MethodAccess handler = this.handler.getHandler();
        final int handleIndex = this.handler.getMethodIndex();
        final IHandlerInvocation invocation = this.invocation;

        Entry current = headREF.get(this);
        Object listener;
        while (current != null) {
            listener = current.getValue();
            current = current.next();

            invocation.invoke(listener, handler, handleIndex, message1, message2, message3);
        }
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
