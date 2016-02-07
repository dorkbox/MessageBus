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
package dorkbox.messagebus.subscription;

import com.esotericsoftware.kryo.util.IdentityMap;
import dorkbox.messagebus.MessageBus;
import dorkbox.messagebus.common.MultiClass;
import dorkbox.messagebus.subscription.reflection.ReflectionFactory;
import dorkbox.messagebus.common.ClassTree;
import dorkbox.messagebus.common.MessageHandler;
import dorkbox.messagebus.subscription.asm.AsmFactory;
import dorkbox.messagebus.utils.ClassUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
/**
 * Permits subscriptions with a varying length of parameters as the signature, which must be match by the publisher for it to be accepted
 *
 *
 * The subscription managers responsibility is to consistently handle and synchronize the message listener subscription process.
 * It provides fast lookup of existing subscriptions when another instance of an already known
 * listener is subscribed and takes care of creating new set of subscriptions for any unknown class that defines
 * message handlers.
 *
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
@SuppressWarnings({"unchecked", "ToArrayCallWithZeroLengthArrayArgument"})
public final
class SubscriptionManager {
    public static final float LOAD_FACTOR = 0.8F;
    private static final Subscription[] EMPTY_SUBS = new Subscription[0];

    // controls if we use java reflection or ASM to access methods during publication
    private final SubscriptionFactory subscriptionFactory;


    // ONLY used by SUB/UNSUB
    // remember already processed classes that do not contain any message handlers
    private final IdentityMap<Class<?>, Boolean> nonListeners;

    // ONLY used by SUB/UNSUB
    // all subscriptions per messageHandler type
    // this map provides fast access for subscribing and unsubscribing
    // once a collection of subscriptions is stored it does not change
    private final IdentityMap<Class<?>, Subscription[]> subsPerListener;

    // We perpetually KEEP the types registered here, and just change what is sub/unsub

    // all subscriptions of a message type.
    private volatile IdentityMap<Class<?>, Subscription[]> subsSingle;
    private volatile IdentityMap<MultiClass, Subscription[]> subsMulti;

    // keeps track of all subscriptions of the super classes of a message type.
    private volatile IdentityMap<Class<?>, Subscription[]> subsSuperSingle;
    private volatile IdentityMap<MultiClass, Subscription[]> subsSuperMulti;

    // In order to force the "single writer principle" for subscribe & unsubscribe, they are within SYNCHRONIZED.
    //
    // These methods **COULD** be dispatched via another thread (so it's only one thread ever touching them), however we do NOT want them
    // asynchronous - as publish() should ALWAYS succeed if a correct subscribe() is called before. 'Synchronized' is good enough here.
    private final Object singleWriterLock = new Object();


    private final ClassTree<Class<?>> classTree;
    private final ClassUtils classUtils;


    // Recommended for best performance while adhering to the "single writer principle". Must be static-final
    private static final AtomicReferenceFieldUpdater<SubscriptionManager, IdentityMap> subsSingleREF =
                    AtomicReferenceFieldUpdater.newUpdater(SubscriptionManager.class,
                                                           IdentityMap.class,
                                                           "subsSingle");

    private static final AtomicReferenceFieldUpdater<SubscriptionManager, IdentityMap> subsMultiREF =
                    AtomicReferenceFieldUpdater.newUpdater(SubscriptionManager.class,
                                                           IdentityMap.class,
                                                           "subsMulti");


    private static final AtomicReferenceFieldUpdater<SubscriptionManager, IdentityMap> subsSuperSingleREF =
                    AtomicReferenceFieldUpdater.newUpdater(SubscriptionManager.class,
                                                           IdentityMap.class,
                                                           "subsSuperSingle");

    private static final AtomicReferenceFieldUpdater<SubscriptionManager, IdentityMap> subsSuperMultiREF =
                    AtomicReferenceFieldUpdater.newUpdater(SubscriptionManager.class,
                                                           IdentityMap.class,
                                                           "subsSuperMulti");

    public
    SubscriptionManager() {
        // not all platforms support ASM. ASM is our default, and is just-as-fast and directly invoking the method
        if (MessageBus.useAsmForDispatch) {
            this.subscriptionFactory = new AsmFactory();
        }
        else {
            this.subscriptionFactory = new ReflectionFactory();
        }

        classUtils = new ClassUtils();
        classTree = new ClassTree<Class<?>>();


        // modified ONLY during SUB/UNSUB
        nonListeners = new IdentityMap<Class<?>, Boolean>(16, LOAD_FACTOR);
        subsPerListener = new IdentityMap<Class<?>, Subscription[]>(32, LOAD_FACTOR);
        subsSingle = new IdentityMap<Class<?>, Subscription[]>(32, LOAD_FACTOR);
        subsMulti = new IdentityMap<MultiClass, Subscription[]>(32, LOAD_FACTOR);


        // modified during publication, however duplicates are OK, we we can "pretend" it's the same as the single-writer-principle
        subsSuperSingle = new IdentityMap<Class<?>, Subscription[]>(32, LOAD_FACTOR);
        subsSuperMulti = new IdentityMap<MultiClass, Subscription[]>(32, LOAD_FACTOR);
    }

    /**
     * Shuts down and clears all memory usage by the subscriptions
     */
    public
    void shutdown() {

        // explicitly clear out the subscriptions
        final IdentityMap.Entries<Class<?>, Subscription[]> entries = subsPerListener.entries();
        for (IdentityMap.Entry<Class<?>, Subscription[]> entry : entries) {
            final Subscription[] subscriptions = entry.value;
            if (subscriptions != null) {
                Subscription subscription;

                for (int i = 0; i < subscriptions.length; i++) {
                    subscription = subscriptions[i];
                    subscription.clear();
                }
            }
        }

        this.nonListeners.clear();

        this.subsPerListener.clear();

        this.subsSingle.clear();
        this.subsMulti.clear();

        this.subsSuperSingle.clear();
        this.subsSuperMulti.clear();

        this.classTree.clear();
        this.classUtils.shutdown();
    }

    /**
     * Subscribes a specific listener. The infrastructure for subscription never "shrinks", meaning that when a listener is un-subscribed,
     * the listeners are only removed from the internal map -- the map itself is not cleaned up until a 'shutdown' is called.
     *
     * This method uses the "single-writer-principle" for lock-free publication. Since there are only 2
     * methods to guarantee this method can only be called one-at-a-time (either it is only called by one thread, or only one thread can
     * access it at a time) -- we chose the 2nd option -- and use a 'synchronized' block to make sure that only one thread can access
     * this method at a time.
     */
    public
    void subscribe(final Object listener) {
        final Class<?> listenerClass = listener.getClass();

        // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
        // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
        // use-case 99% of the time)
        synchronized (singleWriterLock) {
            final IdentityMap<Class<?>, Boolean> nonListeners = this.nonListeners;
            if (nonListeners.containsKey(listenerClass)) {
                // early reject of known classes that do not define message handlers
                return;
            }

            // this is an array, because subscriptions for a specific listener CANNOT change, either they exist or do not exist.
            // ONCE subscriptions are in THIS map, they are considered AVAILABLE.
            Subscription[] subscriptions = subsPerListener.get(listenerClass);

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
                final IdentityMap<Class<?>, Subscription[]> singleSubs = subsSingleREF.get(this);
                final IdentityMap<MultiClass, Subscription[]> multiSubs = subsMultiREF.get(this);

                Subscription subscription;

                MessageHandler messageHandler;
                Class<?>[] messageHandlerTypes;
                int messageHandlerTypesSize;

                MultiClass multiClass;
                Class<?> handlerType;


                // Prepare all of the subscriptions and add for publication AND subscribe since the data structures are consistent
                for (int i = 0; i < handlersSize; i++) {
                    messageHandler = messageHandlers[i];

                    subscription = subscriptionFactory.create(listenerClass, messageHandler);
                    subscription.subscribe(listener);  // register this callback listener to this subscription
                    subscriptions[i] = subscription;

                    // register for publication
                    messageHandlerTypes = messageHandler.getHandledMessages();
                    messageHandlerTypesSize = messageHandlerTypes.length;

                    switch (messageHandlerTypesSize) {
                        case 0: {
                            // if a publisher publishes VOID, it calls a method with 0 parameters (that's been subscribed)
                            // This is the SAME THING as having Void as a parameter!!
                            handlerType = Void.class;


                            // makes this subscription visible for publication
                            final Subscription[] newSubs;
                            Subscription[] currentSubs = singleSubs.get(handlerType);
                            if (currentSubs != null) {
                                final int currentLength = currentSubs.length;

                                // add the new subscription to the array
                                newSubs = Arrays.copyOf(currentSubs, currentLength + 1, Subscription[].class);
                                newSubs[currentLength] = subscription;
                            } else {
                                newSubs = new Subscription[1];
                                newSubs[0] = subscription;
                            }

                            singleSubs.put(handlerType, newSubs);
                            break;
                        }

                        case 1: {
                            handlerType = messageHandlerTypes[0];

                            // makes this subscription visible for publication
                            final Subscription[] newSubs;
                            Subscription[] currentSubs = singleSubs.get(handlerType);
                            if (currentSubs != null) {
                                final int currentLength = currentSubs.length;

                                // add the new subscription to the array
                                newSubs = Arrays.copyOf(currentSubs, currentLength + 1, Subscription[].class);
                                newSubs[currentLength] = subscription;
                            } else {
                                newSubs = new Subscription[1];
                                newSubs[0] = subscription;
                            }

                            singleSubs.put(handlerType, newSubs);

                            break;
                        }

                        case 2: {
                            multiClass = classTree.get(messageHandlerTypes[0], messageHandlerTypes[1]);

                            // makes this subscription visible for publication
                            final Subscription[] newSubs;
                            Subscription[] currentSubs = multiSubs.get(multiClass);

                            if (currentSubs != null) {
                                final int currentLength = currentSubs.length;

                                // add the new subscription to the array
                                newSubs = Arrays.copyOf(currentSubs, currentLength + 1, Subscription[].class);
                                newSubs[currentLength] = subscription;
                            } else {
                                newSubs = new Subscription[1];
                                newSubs[0] = subscription;
                            }

                            multiSubs.put(multiClass, newSubs);
                            break;
                        }

                        case 3: {
                            multiClass = classTree.get(messageHandlerTypes[0], messageHandlerTypes[1], messageHandlerTypes[2]);

                            // makes this subscription visible for publication
                            final Subscription[] newSubs;
                            Subscription[] currentSubs = multiSubs.get(multiClass);

                            if (currentSubs != null) {
                                final int currentLength = currentSubs.length;

                                // add the new subscription to the array
                                newSubs = Arrays.copyOf(currentSubs, currentLength + 1, Subscription[].class);
                                newSubs[currentLength] = subscription;
                            } else {
                                newSubs = new Subscription[1];
                                newSubs[0] = subscription;
                            }

                            multiSubs.put(multiClass, newSubs);
                            break;
                        }

                        default: {
                            throw new RuntimeException("Unsupported number of parameters during subscribe. Acceptable max is 3");
                        }
                    }
                }

                // activates this sub for sub/unsub (only used by the subscription writer thread)
                subsPerListener.put(listenerClass, subscriptions);


                // save this snapshot back to the original (single writer principle)
                subsSingleREF.lazySet(this, singleSubs);
                subsMultiREF.lazySet(this, multiSubs);


                // only dump the super subscriptions if it is a COMPLETELY NEW subscription.
                // If it's not new, then the hierarchy isn't changing for super subscriptions
                IdentityMap<Class<?>, Subscription[]> superSingleSubs = subsSuperSingleREF.get(this);
                superSingleSubs.clear();
                subsSuperSingleREF.lazySet(this, superSingleSubs);

                IdentityMap<MultiClass, Subscription[]> superMultiSubs = subsSuperMultiREF.get(this);
                superMultiSubs.clear();
                subsSuperMultiREF.lazySet(this, superMultiSubs);
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
    }


    /**
     * Un-subscribes a specific listener. The infrastructure for subscription never "shrinks", meaning that when a listener is un-subscribed,
     * the listeners are only removed from the internal map -- the map itself is not cleaned up until a 'shutdown' is called.
     *
     * This method uses the "single-writer-principle" for lock-free publication. Since there are only 2
     * methods to guarantee this method can only be called one-at-a-time (either it is only called by one thread, or only one thread can
     * access it at a time) -- we chose the 2nd option -- and use a 'synchronized' block to make sure that only one thread can access
     * this method at a time.
     */
    public
    void unsubscribe(final Object listener) {
        final Class<?> listenerClass = listener.getClass();

        // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
        // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
        // use-case 99% of the time)
        synchronized (singleWriterLock) {
            if (nonListeners.containsKey(listenerClass)) {
                // early reject of known classes that do not define message handlers
                return;
            }

            final Subscription[] subscriptions = subsPerListener.get(listenerClass);
            if (subscriptions != null) {
                Subscription subscription;

                for (int i = 0; i < subscriptions.length; i++) {
                    subscription = subscriptions[i];
                    subscription.unsubscribe(listener);
                }
            }
        }
    }


    /**
     * @return can return null
     */
    public
    Subscription[] getSubs(final Class<?> messageClass) {
        return (Subscription[]) subsSingleREF.get(this).get(messageClass);
    }


    /**
     * @return can return null
     */
    public
    Subscription[] getSubs(final Class<?> messageClass1, final Class<?> messageClass2) {
        // never returns null
        final MultiClass multiClass = classTree.get(messageClass1,
                                                    messageClass2);
        return (Subscription[]) subsMultiREF.get(this).get(multiClass);
    }

    /**
     * @return can return null
     */
    public
    Subscription[] getSubs(final Class<?> messageClass1, final Class<?> messageClass2, final Class<?> messageClass3) {
        // never returns null
        final MultiClass multiClass = classTree.get(messageClass1,
                                                    messageClass2,
                                                    messageClass3);
        return (Subscription[]) subsMultiREF.get(this).get(multiClass);
    }

    /**
     * @return can NOT return null
     */
    public
    Subscription[] getSuperSubs(final Class<?> messageClass) {
        // The subscriptions that are remembered here DO NOT CHANGE (only the listeners inside them change).
        // if we subscribe a NEW LISTENER super/child class -- THEN these subscriptions change!
        // we also DO NOT care about duplicates (since they will be the same anyways)
        final IdentityMap<Class<?>, Subscription[]> localSuperSubs = subsSuperSingleREF.get(this);

        Subscription[] subscriptions = localSuperSubs.get(messageClass);
        // the only time this is null, is when subscriptions DO NOT exist, and they haven't been calculated. Otherwise, if they are
        // calculated and if they do not exist - this will be an empty array.
        if (subscriptions == null) {
            final Class<?>[] superClasses = this.classUtils.getSuperClasses(messageClass);  // never returns null, cached response

            final int length = superClasses.length;
            final ArrayList<Subscription> subsAsList = new ArrayList<Subscription>(length);

            final IdentityMap<Class<?>, Subscription[]> localSubs = subsSingleREF.get(this);

            Class<?> superClass;
            Subscription sub;
            Subscription[] superSubs;

            // walks through all of the subscriptions that might exist for super types, and if applicable, save them
            for (int i = 0; i < length; i++) {
                superClass = superClasses[i];
                superSubs = localSubs.get(superClass);

                if (superSubs != null) {
                    int superSubLength = superSubs.length;
                    for (int j = 0; j < superSubLength; j++) {
                        sub = superSubs[j];

                        if (sub.getHandler().acceptsSubtypes()) {
                            subsAsList.add(sub);
                        }
                    }
                }
            }

            // subsAsList now contains ALL of the super-class subscriptions.
            subscriptions = subsAsList.toArray(EMPTY_SUBS);
            localSuperSubs.put(messageClass, subscriptions);

            subsSuperSingleREF.lazySet(this, localSuperSubs);
        }

        return subscriptions;
    }

    /**
     * @return can NOT return null
     */
    public
    Subscription[] getSuperSubs(final Class<?> messageClass1, final Class<?> messageClass2) {
        // save the subscriptions
        final Class<?>[] superClasses1 = this.classUtils.getSuperClasses(messageClass1);  // never returns null, cached response
        final Class<?>[] superClasses2 = this.classUtils.getSuperClasses(messageClass2);  // never returns null, cached response

        final MultiClass origMultiClass = classTree.get(messageClass1, messageClass2);

        IdentityMap<MultiClass, Subscription[]> localSuperSubs = subsSuperMultiREF.get(this);
        Subscription[] subscriptions = localSuperSubs.get(origMultiClass);
        // the only time this is null, is when subscriptions DO NOT exist, and they haven't been calculated. Otherwise, if they are
        // calculated and if they do not exist - this will be an empty array.
        if (subscriptions == null) {
            final IdentityMap<MultiClass, Subscription[]> localSubs = subsMultiREF.get(this);

            Class<?> superClass1;
            Class<?> superClass2;
            Subscription sub;
            Subscription[] superSubs;


            final int length1 = superClasses1.length;
            final int length2 = superClasses2.length;

            ArrayList<Subscription> subsAsList = new ArrayList<Subscription>(length1 + length2);

            for (int i = 0; i < length1; i++) {
                superClass1 = superClasses1[i];

                // only go over subtypes
                if (superClass1 == messageClass1) {
                    continue;
                }

                for (int j = 0; j < length2; j++) {
                    superClass2 = superClasses2[j];

                    // only go over subtypes
                    if (superClass2 == messageClass2) {
                        continue;
                    }

                    // never returns null
                    MultiClass multiClass = classTree.get(superClass1,
                                                          superClass2);

                    superSubs = localSubs.get(multiClass);

                    //noinspection Duplicates
                    if (superSubs != null) {
                        for (int k = 0; k < superSubs.length; k++) {
                            sub = superSubs[k];

                            if (sub.getHandler().acceptsSubtypes()) {
                                subsAsList.add(sub);
                            }
                        }
                    }
                }
            }

            // subsAsList now contains ALL of the super-class subscriptions.
            subscriptions = subsAsList.toArray(EMPTY_SUBS);
            localSuperSubs.put(origMultiClass, subscriptions);

            subsSuperMultiREF.lazySet(this, localSuperSubs);
        }

        return subscriptions;
    }

    /**
     * @return can NOT return null
     */
    public
    Subscription[] getSuperSubs(final Class<?> messageClass1, final Class<?> messageClass2, final Class<?> messageClass3) {
        // save the subscriptions
        final Class<?>[] superClasses1 = this.classUtils.getSuperClasses(messageClass1);  // never returns null, cached response
        final Class<?>[] superClasses2 = this.classUtils.getSuperClasses(messageClass2);  // never returns null, cached response
        final Class<?>[] superClasses3 = this.classUtils.getSuperClasses(messageClass3);  // never returns null, cached response

        final MultiClass origMultiClass = classTree.get(messageClass1, messageClass2, messageClass3);

        IdentityMap<MultiClass, Subscription[]> localSuperSubs = subsSuperMultiREF.get(this);
        Subscription[] subscriptions = localSuperSubs.get(origMultiClass);
        // the only time this is null, is when subscriptions DO NOT exist, and they haven't been calculated. Otherwise, if they are
        // calculated and if they do not exist - this will be an empty array.
        if (subscriptions == null) {
            final IdentityMap<MultiClass, Subscription[]> localSubs = subsMultiREF.get(this);



            Class<?> superClass1;
            Class<?> superClass2;
            Class<?> superClass3;
            Subscription sub;
            Subscription[] superSubs;

            final int length1 = superClasses1.length;
            final int length2 = superClasses2.length;
            final int length3 = superClasses3.length;

            ArrayList<Subscription> subsAsList = new ArrayList<Subscription>(length1 + length2);

            for (int i = 0; i < length1; i++) {
                superClass1 = superClasses1[i];

                // only go over subtypes
                if (superClass1 == messageClass1) {
                    continue;
                }

                for (int j = 0; j < length2; j++) {
                    superClass2 = superClasses2[j];

                    // only go over subtypes
                    if (superClass2 == messageClass2) {
                        continue;
                    }

                    for (int k = 0; k < length3; k++) {
                        superClass3 = superClasses3[j];

                        // only go over subtypes
                        if (superClass3 == messageClass3) {
                            continue;
                        }

                        // never returns null
                        MultiClass multiClass = classTree.get(superClass1,
                                                              superClass2,
                                                              superClass3);

                        superSubs = localSubs.get(multiClass);

                        //noinspection Duplicates
                        if (superSubs != null) {
                            for (int m = 0; m < superSubs.length; m++) {
                                sub = superSubs[m];

                                if (sub.getHandler().acceptsSubtypes()) {
                                    subsAsList.add(sub);
                                }
                            }
                        }
                    }
                }
            }

            // subsAsList now contains ALL of the super-class subscriptions.
            subscriptions = subsAsList.toArray(EMPTY_SUBS);
            localSuperSubs.put(origMultiClass, subscriptions);

            subsSuperMultiREF.lazySet(this, localSuperSubs);
        }

        return subscriptions;
    }
}
