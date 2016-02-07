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
package dorkbox.messagebus.subscription;

import com.esotericsoftware.kryo.util.IdentityMap;
import dorkbox.messagebus.common.MessageHandler;

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
class Subscription<T> {
    private static final AtomicInteger ID_COUNTER = new AtomicInteger();
    private final int ID = ID_COUNTER.getAndIncrement();

    // this is the listener class that created this subscription
    private final Class<?> listenerClass;

    // the handler's metadata -> for each handler in a listener, a unique subscription context is created
    private final MessageHandler handler;

    // This is only touched by a single thread!
    private final IdentityMap<Object, Entry> entries; // maintain a map of entries for FAST lookup during unsubscribe.

    // this is still inside the single-writer, and can use the same techniques as subscription manager (for thread safe publication)
    protected volatile Entry<T> head = null; // reference to the first element

    // Recommended for best performance while adhering to the "single writer principle". Must be static-final
    protected static final AtomicReferenceFieldUpdater<Subscription, Entry> headREF =
                    AtomicReferenceFieldUpdater.newUpdater(Subscription.class,
                                                           Entry.class,
                                                           "head");

    protected
    Subscription(final Class<?> listenerClass, final MessageHandler handler) {
        this.listenerClass = listenerClass;
        this.handler = handler;
        this.entries = new IdentityMap<Object, Entry>(32, SubscriptionManager.LOAD_FACTOR);
    }

    // called on shutdown for GC purposes
    public final
    void clear() {
        this.entries.clear();
        this.head = null;
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

    public abstract
    Entry<T> createEntry(final Object listener, final Entry head);

    /**
     * single writer principle!
     * called from within SYNCHRONIZE
     *
     * @param listener the object that will receive messages during publication
     */
    public
    void subscribe(final Object listener) {
        Entry head = headREF.get(this);

        if (!entries.containsKey(listener)) {
            head = createEntry(listener, head);

            entries.put(listener, head);
            headREF.lazySet(this, head);
        }
    }

    /**
     * single writer principle!
     * called from within SYNCHRONIZE
     *
     * @param listener the object that will NO LONGER receive messages during publication
     */
    public
    void unsubscribe(final Object listener) {
        Entry entry = entries.get(listener);

        if (entry != null) {
            removeNode(entry);

            this.entries.remove(listener);
        }
    }

    /**
     * single writer principle!
     * called from within SYNCHRONIZE
     *
     * @param entry the entry that will be removed from the linked list
     */
    protected
    void removeNode(final Entry entry) {
        Entry head = headREF.get(this);

        if (entry == head) {
            // if it was second, now it's first
            head = head.next();
            //oldHead.clear(); // optimize for GC not possible because of potentially running iterators
        }
        else {
            entry.remove();
        }
        headREF.lazySet(this, head);
    }

    /**
     * only used in unit tests
     */
    public final
    int size() {
        return this.entries.size;
    }

    public abstract
    void publish(final Object message) throws Throwable;

    public abstract
    void publish(final Object message1, final Object message2) throws Throwable;

    public abstract
    void publish(final Object message1, final Object message2, final Object message3) throws Throwable;


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
