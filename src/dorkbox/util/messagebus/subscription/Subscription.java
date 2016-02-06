/*
 * Copyright 2016 dorkbox, llc
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
import dorkbox.util.messagebus.common.Entry;
import dorkbox.util.messagebus.common.MessageHandler;

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
 * @author dorkbox, llc
 *         Date: 2/3/16
 */
public abstract
class Subscription {
    private static final AtomicInteger ID_COUNTER = new AtomicInteger();
    public final int ID = ID_COUNTER.getAndIncrement();


    // this is the listener class that created this subscription
    private final Class<?> listenerClass;

    // the handler's metadata -> for each handler in a listener, a unique subscription context is created
    private final MessageHandler handler;

    // Recommended for best performance while adhering to the "single writer principle". Must be static-final
    protected static final AtomicReferenceFieldUpdater<Subscription, Entry> headREF =
                    AtomicReferenceFieldUpdater.newUpdater(Subscription.class,
                                                           Entry.class,
                                                           "head");

    // This is only touched by a single thread!
    private final IdentityMap<Object, Entry> entries; // maintain a map of entries for FAST lookup during unsubscribe.

    // this is still inside the single-writer, and can use the same techniques as subscription manager (for thread safe publication)
    public volatile Entry head; // reference to the first element

    protected
    Subscription(final Class<?> listenerClass, final MessageHandler handler) {
        this.listenerClass = listenerClass;

        this.handler = handler;

        entries = new IdentityMap<Object, Entry>(32, SubscriptionManager.LOAD_FACTOR);


        if (handler.acceptsSubtypes()) {
            // TODO keep a list of "super-class" messages that access this. This is updated by multiple threads. This is so we know WHAT
            // super-subscriptions to clear when we sub/unsub
        }
    }

    // called on shutdown for GC purposes
    public final
    void clear() {
        this.entries.clear();
        this.head.clear();
    }

    // only used in unit tests to verify that the subscription manager is working correctly
    public final
    Class<?> getListenerClass() {
        return listenerClass;
    }

    public final
    MessageHandler getHandler() {
        return handler;
    }

    public final
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
    public final
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
    public final
    int size() {
        return this.entries.size;
    }

    /**
     * @return true if messages were published
     */
    public abstract
    boolean publish(final Object message) throws Throwable;

    /**
     * @return true if messages were published
     */
    public abstract
    boolean publish(final Object message1, final Object message2) throws Throwable;

    /**
     * @return true if messages were published
     */
    public abstract
    boolean publish(final Object message1, final Object message2, final Object message3) throws Throwable;


    @Override
    public final
    int hashCode() {
        return this.ID;
    }

    @Override
    public final
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
