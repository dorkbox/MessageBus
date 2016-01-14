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

import dorkbox.util.messagebus.common.HashMapTree;
import dorkbox.util.messagebus.common.MessageHandler;
import dorkbox.util.messagebus.common.adapter.JavaVersionAdapter;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.utils.ClassUtils;
import dorkbox.util.messagebus.utils.SubscriptionUtils;
import dorkbox.util.messagebus.utils.VarArgUtils;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Permits subscriptions with a varying length of parameters as the signature, which must be match by the publisher for it to be accepted
 */
public
class Subscriber {
    public static final float LOAD_FACTOR = 0.8F;

    private final ErrorHandlingSupport errorHandler;

    private final SubscriptionUtils subUtils;
    private final VarArgUtils varArgUtils;

    // all subscriptions per message type. We perpetually KEEP the types, as this lowers the amount of locking required
    // this is the primary list for dispatching a specific message
    // write access is synchronized and happens only when a listener of a specific class is registered the first time
    private final Map<Class<?>, ArrayList<Subscription>> subscriptionsPerMessageSingle;
    private final HashMapTree<Class<?>, ArrayList<Subscription>> subscriptionsPerMessageMulti;

    // shortcut publication if we know there is no possibility of varArg (ie: a method that has an array as arguments)
    private final AtomicBoolean varArgPossibility = new AtomicBoolean(false);

    private ThreadLocal<ArrayList<Subscription>> listCache = new ThreadLocal<ArrayList<Subscription>>() {
        @Override
        protected
        ArrayList<Subscription> initialValue() {
            return new ArrayList<Subscription>(8);
        }
    };



    public
    Subscriber(final ErrorHandlingSupport errorHandler, final ClassUtils classUtils) {
        this.errorHandler = errorHandler;

        this.subscriptionsPerMessageSingle = JavaVersionAdapter.concurrentMap(32, LOAD_FACTOR, 1);
        this.subscriptionsPerMessageMulti = new HashMapTree<Class<?>, ArrayList<Subscription>>(4, LOAD_FACTOR);

        this.subUtils = new SubscriptionUtils(classUtils, LOAD_FACTOR);

        // var arg subscriptions keep track of which subscriptions can handle varArgs. SUB/UNSUB dumps it, so it is recreated dynamically.
        // it's a hit on SUB/UNSUB, but improves performance of handlers
        this.varArgUtils = new VarArgUtils(classUtils, LOAD_FACTOR);
    }

    public
    AtomicBoolean getVarArgPossibility() {
        return varArgPossibility;
    }

    public
    VarArgUtils getVarArgUtils() {
        return varArgUtils;
    }

    public
    void clear() {
        this.subUtils.clear();
        this.varArgUtils.clear();
    }

    // inside a write lock
    // add this subscription to each of the handled types
    // to activate this sub for publication
    private
    void registerMulti(final Subscription subscription, final Class<?> listenerClass,
                       final Map<Class<?>, ArrayList<Subscription>> subsPerMessageSingle,
                       final HashMapTree<Class<?>, ArrayList<Subscription>> subsPerMessageMulti, final AtomicBoolean varArgPossibility) {

        final MessageHandler handler = subscription.getHandler();
        final Class<?>[] messageHandlerTypes = handler.getHandledMessages();
        final int size = messageHandlerTypes.length;

        final Class<?> type0 = messageHandlerTypes[0];

        switch (size) {
            case 0: {
                // TODO: maybe this SHOULD be permitted? so if a publisher publishes VOID, it call's a method?
                errorHandler.handleError("Error while trying to subscribe class with zero arguments", listenerClass);
                return;
            }
            case 1: {
                // using ThreadLocal cache's is SIGNIFICANTLY faster for subscribing to new types
                final ArrayList<Subscription> cachedSubs = listCache.get();
                ArrayList<Subscription> subs = subsPerMessageSingle.putIfAbsent(type0, cachedSubs);
                if (subs == null) {
                    listCache.set(new ArrayList<Subscription>(8));
                    subs = cachedSubs;

                    // is this handler able to accept var args?
                    if (handler.getVarArgClass() != null) {
                        varArgPossibility.lazySet(true);
                    }
                }

                subs.add(subscription);
                return;
            }
            case 2: {
                ArrayList<Subscription> subs = subsPerMessageMulti.get(type0, messageHandlerTypes[1]);
                if (subs == null) {
                    subs = new ArrayList<Subscription>();

                    subsPerMessageMulti.put(subs, type0, messageHandlerTypes[1]);
                }

                subs.add(subscription);
                return;
            }
            case 3: {
                ArrayList<Subscription> subs = subsPerMessageMulti.get(type0, messageHandlerTypes[1], messageHandlerTypes[2]);
                if (subs == null) {
                    subs = new ArrayList<Subscription>();

                    subsPerMessageMulti.put(subs, type0, messageHandlerTypes[1], messageHandlerTypes[2]);
                }

                subs.add(subscription);
                return;
            }
            default: {
                ArrayList<Subscription> subs = subsPerMessageMulti.get(messageHandlerTypes);
                if (subs == null) {
                    subs = new ArrayList<Subscription>();

                    subsPerMessageMulti.put(subs, messageHandlerTypes);
                }

                subs.add(subscription);
            }
        }
    }

    public
    void register(final Class<?> listenerClass, final int handlersSize, final Subscription[] subsPerListener) {

        final Map<Class<?>, ArrayList<Subscription>> subsPerMessageSingle = this.subscriptionsPerMessageSingle;
        final HashMapTree<Class<?>, ArrayList<Subscription>> subsPerMessageMulti = this.subscriptionsPerMessageMulti;
        final AtomicBoolean varArgPossibility = this.varArgPossibility;

        Subscription subscription;

        for (int i = 0; i < handlersSize; i++) {
            subscription = subsPerListener[i];

            // activate this subscription for publication
            // now add this subscription to each of the handled types
            registerMulti(subscription, listenerClass, subsPerMessageSingle, subsPerMessageMulti, varArgPossibility);
        }
    }

    public
    void shutdown() {
        this.subscriptionsPerMessageSingle.clear();
        this.subscriptionsPerMessageMulti.clear();

        clear();
    }

    public
    ArrayList<Subscription> getExactAsArray(final Class<?> messageClass) {
        return subscriptionsPerMessageSingle.get(messageClass);
    }

    public
    ArrayList<Subscription> getExactAsArray(final Class<?> messageClass1, final Class<?> messageClass2) {
        return subscriptionsPerMessageMulti.get(messageClass1, messageClass2);
    }

    public
    ArrayList<Subscription> getExactAsArray(final Class<?> messageClass1, final Class<?> messageClass2, final Class<?> messageClass3) {
        return subscriptionsPerMessageMulti.get(messageClass1, messageClass2, messageClass3);
    }

    // can return null
    public
    Subscription[] getExact(final Class<?> messageClass) {
        final ArrayList<Subscription> collection = getExactAsArray(messageClass);

        if (collection != null) {
            final Subscription[] subscriptions = new Subscription[collection.size()];
            collection.toArray(subscriptions);

            return subscriptions;
        }

        return null;
    }

    // can return null
    public
    Subscription[] getExact(final Class<?> messageClass1, final Class<?> messageClass2) {
        final ArrayList<Subscription> collection = getExactAsArray(messageClass1, messageClass2);

        if (collection != null) {
            final Subscription[] subscriptions = new Subscription[collection.size()];
            collection.toArray(subscriptions);

            return subscriptions;
        }

        return null;
    }

    // can return null
    public
    Subscription[] getExact(final Class<?> messageClass1, final Class<?> messageClass2, final Class<?> messageClass3) {

        final ArrayList<Subscription> collection = getExactAsArray(messageClass1, messageClass2, messageClass3);

        if (collection != null) {
            final Subscription[] subscriptions = new Subscription[collection.size()];
            collection.toArray(subscriptions);

            return subscriptions;
        }

        return null;
    }

    // can return null
    public
    Subscription[] getExactAndSuper(final Class<?> messageClass) {
        ArrayList<Subscription> collection = getExactAsArray(messageClass); // can return null

        // now publish superClasses
        final ArrayList<Subscription> superSubscriptions = this.subUtils.getSuperSubscriptions(messageClass, this); // NOT return null

        if (collection != null) {
            collection = new ArrayList<Subscription>(collection);

            if (!superSubscriptions.isEmpty()) {
                collection.addAll(superSubscriptions);
            }
        }
        else if (!superSubscriptions.isEmpty()) {
            collection = superSubscriptions;
        }

        if (collection != null) {
            final Subscription[] subscriptions = new Subscription[collection.size()];
            collection.toArray(subscriptions);
            return subscriptions;
        }
        else {
            return null;
        }
    }

    // can return null
    public
    Subscription[] getExactAndSuper(final Class<?> messageClass1, final Class<?> messageClass2) {
        ArrayList<Subscription> collection = getExactAsArray(messageClass1, messageClass2); // can return null

        // now publish superClasses
        final ArrayList<Subscription> superSubs = this.subUtils.getSuperSubscriptions(messageClass1, messageClass2,
                                                                                      this); // NOT return null

        if (collection != null) {
            collection = new ArrayList<Subscription>(collection);

            if (!superSubs.isEmpty()) {
                collection.addAll(superSubs);
            }
        }
        else if (!superSubs.isEmpty()) {
            collection = superSubs;
        }

        if (collection != null) {
            final Subscription[] subscriptions = new Subscription[collection.size()];
            collection.toArray(subscriptions);
            return subscriptions;
        }
        else {
            return null;
        }
    }

    // can return null
    public
    Subscription[] getExactAndSuper(final Class<?> messageClass1, final Class<?> messageClass2, final Class<?> messageClass3) {

        ArrayList<Subscription> collection = getExactAsArray(messageClass1, messageClass2, messageClass3); // can return null

        // now publish superClasses
        final ArrayList<Subscription> superSubs = this.subUtils.getSuperSubscriptions(messageClass1, messageClass2, messageClass3,
                                                                                      this); // NOT return null

        if (collection != null) {
            collection = new ArrayList<Subscription>(collection);

            if (!superSubs.isEmpty()) {
                collection.addAll(superSubs);
            }
        }
        else if (!superSubs.isEmpty()) {
            collection = superSubs;
        }

        if (collection != null) {
            final Subscription[] subscriptions = new Subscription[collection.size()];
            collection.toArray(subscriptions);
            return subscriptions;
        }
        else {
            return null;
        }
    }
}
