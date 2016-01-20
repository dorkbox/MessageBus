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

import com.esotericsoftware.kryo.util.IdentityMap;
import dorkbox.util.messagebus.common.HashMapTree;
import dorkbox.util.messagebus.common.MessageHandler;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.utils.ClassUtils;
import dorkbox.util.messagebus.utils.SubscriptionUtils;
import dorkbox.util.messagebus.utils.VarArgUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
/**
 * Permits subscriptions with a varying length of parameters as the signature, which must be match by the publisher for it to be accepted
 */
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
    public static final float LOAD_FACTOR = 0.8F;
    public static final Subscription[] SUBSCRIPTIONS = new Subscription[0];

    // TODO: during startup, pre-calculate the number of subscription listeners and x2 to save as subsPerListener expected max size


    // ONLY used by SUB/UNSUB
    // remember already processed classes that do not contain any message handlers
    private final IdentityMap<Class<?>, Boolean> nonListeners;

    // ONLY used by SUB/UNSUB
    // all subscriptions per messageHandler type
    // this map provides fast access for subscribing and unsubscribing
    // once a collection of subscriptions is stored it does not change
    private final IdentityMap<Class<?>, Subscription[]> subsPerListener;


    // all subscriptions per message type. We perpetually KEEP the types
    private volatile IdentityMap<Class<?>, Subscription[]> subsPerMessageSingle;

    // keeps track of all subscriptions of the super classes of a message type.
    private volatile IdentityMap<Class<?>, Subscription[]> subsPerSuperMessageSingle;





    private final ErrorHandlingSupport errorHandler;

    private final SubscriptionUtils subUtils;
    private final VarArgUtils varArgUtils;

    private final HashMapTree<Class<?>, ArrayList<Subscription>> subscriptionsPerMessageMulti;

    // shortcut publication if we know there is no possibility of varArg (ie: a method that has an array as arguments)
    private final AtomicBoolean varArgPossibility = new AtomicBoolean(false);

    private final ClassUtils classUtils;


    // Recommended for best performance while adhering to the "single writer principle"
    private final AtomicReferenceFieldUpdater<SubscriptionManager, IdentityMap> subsPerMessageSingleREF =
                    AtomicReferenceFieldUpdater.newUpdater(SubscriptionManager.class,
                                                          IdentityMap.class,
                                                          "subsPerMessageSingle");

    private final AtomicReferenceFieldUpdater<SubscriptionManager, IdentityMap> subsPerSuperMessageSingleREF =
                    AtomicReferenceFieldUpdater.newUpdater(SubscriptionManager.class,
                                                           IdentityMap.class,
                                                           "subsPerSuperMessageSingle");

//NOTE for multiArg, can use the memory address concatenated with other ones and then just put it in the 'single" map (convert single to
// use this too). it would likely have to be longs  no idea what to do for arrays?? (arrays should verify all the elements are the
// correct type too)

    public
    SubscriptionManager(final int numberOfThreads, final ErrorHandlingSupport errorHandler) {
        this.errorHandler = errorHandler;

        classUtils = new ClassUtils(SubscriptionManager.LOAD_FACTOR);

        // modified ONLY during SUB/UNSUB
        nonListeners = new IdentityMap<Class<?>, Boolean>(16, LOAD_FACTOR);
        subsPerListener = new IdentityMap<>(32, LOAD_FACTOR);
        subsPerMessageSingle = new IdentityMap<Class<?>, Subscription[]>(32, LOAD_FACTOR);
        subsPerSuperMessageSingle = new IdentityMap<Class<?>, Subscription[]>(32, LOAD_FACTOR);




        this.subscriptionsPerMessageMulti = new HashMapTree<Class<?>, ArrayList<Subscription>>();

        this.subUtils = new SubscriptionUtils(classUtils, LOAD_FACTOR, numberOfThreads);

        // var arg subscriptions keep track of which subscriptions can handle varArgs. SUB/UNSUB dumps it, so it is recreated dynamically.
        // it's a hit on SUB/UNSUB, but improves performance of handlers
        this.varArgUtils = new VarArgUtils(classUtils, LOAD_FACTOR, numberOfThreads);


    }

    public
    void shutdown() {
        this.nonListeners.clear();

        this.subsPerMessageSingle.clear();
        this.subsPerSuperMessageSingle.clear();
        this.subscriptionsPerMessageMulti.clear();

        this.subsPerListener.clear();
        this.classUtils.shutdown();
        clear();
    }

    public
    void subscribe(final Object listener) {
        // when subscribing, this is a GREAT opportunity to figure out the classes/objects loaded -- their hierarchy, AND generate UUIDs
        // for each CLASS that can be accessed. This then lets us lookup a UUID for each object that comes in -- if an ID is found (for
        // any part of it's object hierarchy) -- it  means that we have that listeners for that object. this is MUCH faster checking if
        // we have subscriptions first (and failing).
        //
        // so during subscribe we can check "getUUID for all parameter.class accessed by this listener" -> then during publish "lookup
        // UUID of incoming message.class" (+ it's super classes, if necessary) -> then check if UUID exists. If yes, then we know there
        // are subs. if no - then it's a dead message.
        //
        // This lets us accomplish TWO things
        // 1) be able quickly determine if there are dead messages
        // 2) be able to create "multi-class" UUIDs, when two+ classes are represented (always) by the same UUID, by a clever mixing of
        //    the classes individual UUIDs.
        //
        // The generation of UUIDs happens ONLY during subscribe, and during publish they are looked up. This UUID can be a simple
        // AtomicInteger that starts a MIN_VALUE and count's up.


        // note: we can do PRE-STARTUP instrumentation (ie, BEFORE any classes are loaded by the classloader) and inject the UUID into
        // every object (as a public static final field), then use reflection to look up this value. It would go something like this:
        // 1) scan every class for annotations that match
        // 2) for each method that has our annotation -- get the list of classes + hierarchy that are the parameters for the method
        // 3) inject the UUID field into each class object that #2 returns, only if it doesn't already exist. use invalid field names
        // (ie: start with numbers or ? or ^ or something
        //
        // then during SUB/UNSUB/PUB, we use this UUID for everything (and we can have multi-UUID lookups for the 'multi-arg' thing).
        //  If there is no UUID, then we just abort the SUB/UNSUB or send a deadmessage


        final Class<?> listenerClass = listener.getClass();

        if (this.nonListeners.containsKey(listenerClass)) {
            // early reject of known classes that do not define message handlers
            return;
        }

        // these are concurrent collections
        clear();

        // this is an array, because subscriptions for a specific listener CANNOT change, either they exist or do not exist.
        // ONCE subscriptions are in THIS map, they are considered AVAILABLE.
        Subscription[] subscriptions = this.subsPerListener.get(listenerClass);

        // the subscriptions from the map were null, so create them
        if (subscriptions == null) {
            final MessageHandler[] messageHandlers = MessageHandler.get(listenerClass);
            final int handlersSize = messageHandlers.length;

            // remember the class as non listening class if no handlers are found
            if (handlersSize == 0) {
                this.nonListeners.put(listenerClass, Boolean.TRUE);
                return;
            }

            // create the subscriptions
            subscriptions = new Subscription[handlersSize];

            // access a snapshot of the subscriptions (single-writer-principle)
            final IdentityMap<Class<?>, Subscription[]> local = subsPerMessageSingleREF.get(this);

            Subscription subscription;

            MessageHandler messageHandler;
            Class<?>[] messageHandlerTypes;
            Class<?> handlerType;

            for (int i = 0; i < handlersSize; i++) {
                // THE HANDLER IS THE SAME FOR ALL SUBSCRIPTIONS OF THE SAME TYPE!
                messageHandler = messageHandlers[i];

                // is this handler able to accept var args?
                if (messageHandler.getVarArgClass() != null) {
                    varArgPossibility.lazySet(true);
                }

                // now create a list of subscriptions for this specific handlerType (but don't add anything yet).
                // we only store things based on the FIRST type (for lookup) then parse the rest of the types during publication
                messageHandlerTypes = messageHandler.getHandledMessages();
                handlerType = messageHandlerTypes[0];

                if (!local.containsKey(handlerType)) {
                    local.put(handlerType, SUBSCRIPTIONS);  // this is copied to a larger array if necessary
                }

                // create the subscription. This can be thrown away if the subscription succeeds in another thread
                subscription = new Subscription(messageHandler);
                subscriptions[i] = subscription;
            }

            // now subsPerMessageSingle has a unique list of subscriptions for a specific handlerType, and MAY already have subscriptions


            subsPerListener.put(listenerClass, subscriptions); // activates this sub for sub/unsub

            // we can now safely add for publication AND subscribe since the data structures are consistent
            for (int i = 0; i < handlersSize; i++) {
                subscription = subscriptions[i];
                subscription.subscribe(listener);  // register this callback listener to this subscription

                // THE HANDLER IS THE SAME FOR ALL SUBSCRIPTIONS OF THE SAME TYPE!
                messageHandler = messageHandlers[i];

                // register for publication
                messageHandlerTypes = messageHandler.getHandledMessages();
                handlerType = messageHandlerTypes[0];


                // makes this subscription visible for publication
                final Subscription[] currentSubs = local.get(handlerType);
                final int currentLength = currentSubs.length;

                // add the new subscription to the beginning of the array
                final Subscription[] newSubs = new Subscription[currentLength + 1];
                newSubs[0] = subscription;
                System.arraycopy(currentSubs, 0, newSubs, 1, currentLength);
                local.put(handlerType, newSubs);

                // update the super types
                registerSuperSubs(handlerType, local);
            }

            // save this snapshot back to the original (single writer principle)
            subsPerMessageSingleREF.lazySet(this, local);
        }
        else {
            // subscriptions already exist and must only be updated
            Subscription subscription;
            for (int i = 0; i < subscriptions.length; i++) {
                subscription = subscriptions[i];
                subscription.subscribe(listener);
            }
        }
    }

    public
    void unsubscribe(final Object listener) {
        final Class<?> listenerClass = listener.getClass();

        if (this.nonListeners.containsKey(listenerClass)) {
            // early reject of known classes that do not define message handlers
            return;
        }

        // these are concurrent collections
        clear();

        final Subscription[] subscriptions = this.subsPerListener.get(listenerClass);
        if (subscriptions != null) {
            Subscription subscription;

            for (int i = 0; i < subscriptions.length; i++) {
                subscription = subscriptions[i];
                subscription.unsubscribe(listener);
            }
        }
    }

    private
    void registerSuperSubs(final Class<?> clazz, final IdentityMap<Class<?>, Subscription[]> subsPerMessageSingle) {
        final Class<?>[] superClasses = this.classUtils.getSuperClasses(clazz);  // never returns null, cached response

        // access a snapshot of the subscriptions (single-writer-principle)
        final IdentityMap<Class<?>, Subscription[]> local = subsPerSuperMessageSingleREF.get(this);

        // types was not empty, so collect subscriptions for each type and collate them
        // save the subscriptions

        Class<?> superClass;
        Subscription[] superSubs;
        Subscription sub;

        final int length = superClasses.length;
        int superSubLength;
        final ArrayList<Subscription> subsAsList = new ArrayList<Subscription>(length);

        // walks through all of the subscriptions that might exist for super types, and if applicable, save them
        for (int i = 0; i < length; i++) {
            superClass = superClasses[i];
            superSubs = subsPerMessageSingle.get(superClass);

            if (superSubs != null) {
                superSubLength = superSubs.length;
                for (int j = 0; j < superSubLength; j++) {
                    sub = superSubs[j];

                    if (sub.getHandler().acceptsSubtypes()) {
                        subsAsList.add(sub);
                    }
                }
            }
        }

        final int size = subsAsList.size();
        if (size > 0) {
            Subscription[] subs = new Subscription[size];
            subsAsList.toArray(subs);
            local.put(clazz, subs);

            // save this snapshot back to the original (single writer principle)
            subsPerSuperMessageSingleREF.lazySet(this, local);
        }
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
//        this.subUtils.clear();
//        this.varArgUtils.clear();
    }

    // inside a write lock
    // add this subscription to each of the handled types
    // to activate this sub for publication
    private
    void registerMulti(final Subscription subscription, final Class<?> listenerClass,
                       final Map<Class<?>, List<Subscription>> subsPerMessageSingle,
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
//                // using ThreadLocal cache's is SIGNIFICANTLY faster for subscribing to new types
//                final List<Subscription> cachedSubs = listCache.get();
//                List<Subscription> subs = subsPerMessageSingle.putIfAbsent(type0, cachedSubs);
//                if (subs == null) {
//                    listCache.set(new CopyOnWriteArrayList<Subscription>());
////                    listCache.set(new ArrayList<Subscription>(8));
//                    subs = cachedSubs;
//
//                    // is this handler able to accept var args?
//                    if (handler.getVarArgClass() != null) {
//                        varArgPossibility.lazySet(true);
//                    }
//                }
//
//                subs.add(subscription);
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

    // can return null
    public
    Subscription[] getExact(final Class<?> messageClass) {
        return (Subscription[]) subsPerMessageSingleREF.get(this).get(messageClass);
//        return subsPerMessageSingle.get(messageClass);
    }

    // can return null
    public
    Subscription[] getSuperExactAsArray(final Class<?> messageClass) {
        // whenever our subscriptions change, this map is cleared.
        return (Subscription[]) subsPerSuperMessageSingleREF.get(this).get(messageClass);
//        return this.subsPerMessageSuperSingle.get(messageClass);
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
        Subscription[] collection = getExact(messageClass); // can return null

        // now publish superClasses
        final Subscription[] superSubscriptions = getSuperExactAsArray(messageClass); // can return null

        if (collection != null) {
            if (superSubscriptions != null) {
                // but both into a single array
                final int length = collection.length;
                final int lengthSuper = superSubscriptions.length;

                final Subscription[] newSubs = new Subscription[length + lengthSuper];
                System.arraycopy(collection, 0, newSubs, 0, length);
                System.arraycopy(superSubscriptions, 0, newSubs, length, lengthSuper);

                return newSubs;
            }

            return collection;
        }

        return superSubscriptions;
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
