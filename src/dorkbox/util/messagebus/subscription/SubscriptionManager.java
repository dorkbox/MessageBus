/*
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

import dorkbox.util.messagebus.common.MessageHandler;
import dorkbox.util.messagebus.common.adapter.JavaVersionAdapter;
import dorkbox.util.messagebus.common.adapter.StampedLock;

import java.util.Map;

/**
 * The subscription managers responsibility is to consistently handle and synchronize the message listener subscription process.
 * It provides fast lookup of existing subscriptions when another instance of an already known
 * listener is subscribed and takes care of creating new set of subscriptions for any unknown class that defines
 * message handlers.
 *
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public final
class SubscriptionManager {
    // remember already processed classes that do not contain any message handlers
    private final Map<Class<?>, Boolean> nonListeners;

    // all subscriptions per messageHandler type
    // this map provides fast access for subscribing and unsubscribing
    // write access is synchronized and happens very infrequently
    // once a collection of subscriptions is stored it does not change
    private final Map<Class<?>, Subscription[]> subscriptionsPerListener;


    private final StampedLock lock;
    private final int numberOfThreads;
    private final Subscriber subscriber;


    public
    SubscriptionManager(final int numberOfThreads, final Subscriber subscriber, final StampedLock lock) {
        this.numberOfThreads = numberOfThreads;
        this.subscriber = subscriber;
        this.lock = lock;


        // modified ONLY during SUB/UNSUB
        this.nonListeners = JavaVersionAdapter.concurrentMap(4, Subscriber.LOAD_FACTOR, numberOfThreads);

        // only used during SUB/UNSUB, in a rw lock
        this.subscriptionsPerListener = JavaVersionAdapter.concurrentMap(32, Subscriber.LOAD_FACTOR, 1);
    }

    public
    void shutdown() {
        this.nonListeners.clear();

        subscriber.shutdown();
        this.subscriptionsPerListener.clear();
    }

    public
    void subscribe(final Object listener) {
        if (listener == null) {
            return;
        }

        final Class<?> listenerClass = listener.getClass();

        if (this.nonListeners.containsKey(listenerClass)) {
            // early reject of known classes that do not define message handlers
            return;
        }

        // these are concurrent collections
        subscriber.clear();

        Subscription[] subscriptions = getListenerSubs(listenerClass);

        // the subscriptions from the map were null, so create them
        if (subscriptions == null) {
            // it is important to note that this section CAN be repeated, however the write lock is gained before
            // anything 'permanent' is saved. This is so the time spent inside the writelock is minimized.

            final MessageHandler[] messageHandlers = MessageHandler.get(listenerClass);
            final int handlersSize = messageHandlers.length;

            // remember the class as non listening class if no handlers are found
            if (handlersSize == 0) {
                this.nonListeners.put(listenerClass, Boolean.TRUE);
                return;
            }

            final Subscription[] subsPerListener = new Subscription[handlersSize];


            // create the subscription
            MessageHandler messageHandler;
            Subscription subscription;

            for (int i = 0; i < handlersSize; i++) {
                messageHandler = messageHandlers[i];

                // create the subscription
                subscription = new Subscription(messageHandler, Subscriber.LOAD_FACTOR, numberOfThreads);
                subscription.subscribe(listener);

                subsPerListener[i] = subscription; // activates this sub for sub/unsub
            }

            final Map<Class<?>, Subscription[]> subsPerListenerMap = this.subscriptionsPerListener;

            // now write lock for the least expensive part. This is a deferred "double checked lock", but is necessary because
            // of the huge number of reads compared to writes.

            final StampedLock lock = this.lock;
            final long stamp = lock.writeLock();

            subscriptions = subsPerListenerMap.get(listenerClass);

            // it was still null, so we actually have to create the rest of the subs
            if (subscriptions == null) {
                subscriber.register(listenerClass, handlersSize, subsPerListener); // this adds to subscriptionsPerMessage

                subsPerListenerMap.put(listenerClass, subsPerListener);
                lock.unlockWrite(stamp);

                return;
            }
            else {
                // continue to subscription
                lock.unlockWrite(stamp);
            }
        }

        // subscriptions already exist and must only be updated
        // only publish here if our single-check was OK, or our double-check was OK
        Subscription subscription;
        for (int i = 0; i < subscriptions.length; i++) {
            subscription = subscriptions[i];
            subscription.subscribe(listener);
        }
    }

    public
    void unsubscribe(final Object listener) {
        if (listener == null) {
            return;
        }

        final Class<?> listenerClass = listener.getClass();
        if (this.nonListeners.containsKey(listenerClass)) {
            // early reject of known classes that do not define message handlers
            return;
        }

        // these are concurrent collections
        subscriber.clear();

        final Subscription[] subscriptions = getListenerSubs(listenerClass);
        if (subscriptions != null) {
            Subscription subscription;

            for (int i = 0; i < subscriptions.length; i++) {
                subscription = subscriptions[i];
                subscription.unsubscribe(listener);
            }
        }
    }

    private
    Subscription[] getListenerSubs(final Class<?> listenerClass) {

        final StampedLock lock = this.lock;
        final long stamp = lock.readLock();

        final Subscription[] subscriptions = this.subscriptionsPerListener.get(listenerClass);

        lock.unlockRead(stamp);
        return subscriptions;
    }
}
