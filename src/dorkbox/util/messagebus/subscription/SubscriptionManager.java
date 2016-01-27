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
import dorkbox.util.messagebus.common.ClassTree;
import dorkbox.util.messagebus.common.MessageHandler;
import dorkbox.util.messagebus.common.MultiClass;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.utils.ClassUtils;
import dorkbox.util.messagebus.utils.SubscriptionUtils;
import dorkbox.util.messagebus.utils.VarArgUtils;

import java.util.ArrayList;
import java.util.Arrays;
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
@SuppressWarnings("unchecked")
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


    // We perpetually KEEP the types registered here, and just change what is sub/unsub

    // all subscriptions of a message type.
    private volatile IdentityMap<Class<?>, Subscription[]> subsSingle;
    private volatile IdentityMap<MultiClass, Subscription[]> subsMulti;

    // keeps track of all subscriptions of the super classes of a message type.
    private volatile IdentityMap<Class<?>, Subscription[]> subsSuperSingle;

    // keeps track of all subscriptions of varity (var-arg) classes of a message type
    private volatile IdentityMap<Class<?>, Subscription[]> subsVaritySingle;

    // keeps track of all subscriptions of super-class varity (var-arg) classes of a message type
    private volatile IdentityMap<Class<?>, Subscription[]> subsSuperVaritySingle;

    // In order to force the "Single writer principle" on subscribe & unsubscribe, they are within WRITE LOCKS. They could be dispatched
    // to another thread, however we do NOT want them asynchronous - as publish() should ALWAYS succeed if a correct subscribe() is
    // called before. A WriteLock doesn't perform any better here than synchronized does.
    private final Object singleWriterLock = new Object();




    private final ErrorHandlingSupport errorHandler;

    private final SubscriptionUtils subUtils;
    private final VarArgUtils varArgUtils;

    private final ClassTree<Class<?>> classTree;

    // shortcut publication if we know there is no possibility of varArg (ie: a method that has an array as arguments)
    private final AtomicBoolean varArgPossibility = new AtomicBoolean(false);

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

    private static final AtomicReferenceFieldUpdater<SubscriptionManager, IdentityMap> subsVaritySingleREF =
                    AtomicReferenceFieldUpdater.newUpdater(SubscriptionManager.class,
                                                           IdentityMap.class,
                                                           "subsVaritySingle");

    private static final AtomicReferenceFieldUpdater<SubscriptionManager, IdentityMap> subsSuperVaritySingleREF =
                    AtomicReferenceFieldUpdater.newUpdater(SubscriptionManager.class,
                                                           IdentityMap.class,
                                                           "subsSuperVaritySingle");

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
        subsSingle = new IdentityMap<Class<?>, Subscription[]>(32, LOAD_FACTOR);
        subsMulti = new IdentityMap<MultiClass, Subscription[]>(32, LOAD_FACTOR);


        subsSuperSingle = new IdentityMap<Class<?>, Subscription[]>(32, LOAD_FACTOR);
        subsVaritySingle = new IdentityMap<Class<?>, Subscription[]>(32, LOAD_FACTOR);
        subsSuperVaritySingle = new IdentityMap<Class<?>, Subscription[]>(32, LOAD_FACTOR);



        this.classTree = new ClassTree<Class<?>>();

        this.subUtils = new SubscriptionUtils(classUtils, LOAD_FACTOR, numberOfThreads);

        // var arg subscriptions keep track of which subscriptions can handle varArgs. SUB/UNSUB dumps it, so it is recreated dynamically.
        // it's a hit on SUB/UNSUB, but improves performance of handlers
        this.varArgUtils = new VarArgUtils(classUtils, LOAD_FACTOR, numberOfThreads);


    }

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
        this.subsSuperSingle.clear();
        this.subsVaritySingle.clear();
        this.subsSuperVaritySingle.clear();

        this.classTree.clear();

        this.classUtils.shutdown();
    }

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
//                final IdentityMap<MultiClass, Subscription[]> multiSubs = subsMultiREF.get(this);

//            final IdentityMap<Class<?>, Subscription[]> localSuperSubs = subsSuperSingleREF.get(this);
//            final IdentityMap<Class<?>, Subscription[]> localVaritySubs = subsVaritySingleREF.get(this);
//            final IdentityMap<Class<?>, Subscription[]> localSuperVaritySubs = subsSuperVaritySingleREF.get(this);

                Subscription subscription;

                MessageHandler messageHandler;
                Class<?>[] messageHandlerTypes;
                Class<?> handlerType;


                // Prepare all of the subscriptions
                for (int i = 0; i < handlersSize; i++) {
                    // THE HANDLER IS THE SAME FOR ALL SUBSCRIPTIONS OF THE SAME TYPE!
                    messageHandler = messageHandlers[i];

                    // is this handler able to accept var args?
//                    if (messageHandler.getVarArgClass() != null) {
//                        varArgPossibility.lazySet(true);
//                    }

                    // now create a list of subscriptions for this specific handlerType (but don't add anything yet).
                    // we only store things based on the FIRST type (for lookup) then parse the rest of the types during publication
                    messageHandlerTypes = messageHandler.getHandledMessages();
//                    final int handlerSize = messageHandlerTypes.length;
//                    switch (handlerSize) {
//                        case 0: {
//                            // if a publisher publishes VOID, it calls a method with 0 parameters (that's been subscribed)
//                            // This is the SAME THING as having Void as a parameter!!
//                            handlerType = Void.class;
//
//                            if (!singleSubs.containsKey(handlerType)) {
//                                // this is copied to a larger array if necessary, but needs to be SOMETHING before subsPerListener is added
//                                singleSubs.put(handlerType, SUBSCRIPTIONS);
//                            }
//                            break;
//                        }
//                        case 1: {
                            handlerType = messageHandlerTypes[0];

                            if (!singleSubs.containsKey(handlerType)) {
                                // this is copied to a larger array if necessary, but needs to be SOMETHING before subsPerListener is added
                                singleSubs.put(handlerType, SUBSCRIPTIONS);
                            }
//                            break;
//                        }
//                        case 2: {
//                            final MultiClass multiClass = classTree.get(messageHandlerTypes[0],
//                                                                        messageHandlerTypes[1]);
//
//                            if (!multiSubs.containsKey(multiClass)) {
//                                // this is copied to a larger array if necessary, but needs to be SOMETHING before subsPerListener is added
//                                multiSubs.put(multiClass, SUBSCRIPTIONS);
//                            }
//                            break;
//                        }
//                        case 3: {
//                            final MultiClass multiClass = classTree.get(messageHandlerTypes[0],
//                                                                        messageHandlerTypes[1],
//                                                                        messageHandlerTypes[2]);
//
//                            if (!multiSubs.containsKey(multiClass)) {
//                                // this is copied to a larger array if necessary, but needs to be SOMETHING before subsPerListener is added
//                                multiSubs.put(multiClass, SUBSCRIPTIONS);
//                            }
//                            break;
//                        }
//                        default: {
//                            final MultiClass multiClass = classTree.get(messageHandlerTypes);
//
//                            if (!multiSubs.containsKey(multiClass)) {
//                                // this is copied to a larger array if necessary, but needs to be SOMETHING before subsPerListener is added
//                                multiSubs.put(multiClass, SUBSCRIPTIONS);
//                            }
//                            break;
//                        }
//                    }

                    // create the subscription. This can be thrown away if the subscription succeeds in another thread
                    subscription = new Subscription(listenerClass, messageHandler);
                    subscriptions[i] = subscription;
                }

                // now subsPerMessageSingle has a unique list of subscriptions for a specific handlerType, and MAY already have subscriptions

                // activates this sub for sub/unsub (only used by the subscription writer thread)
                subsPerListener.put(listenerClass, subscriptions);


                // add for publication AND subscribe since the data structures are consistent
                for (int i = 0; i < handlersSize; i++) {
                    subscription = subscriptions[i];
                    subscription.subscribe(listener);  // register this callback listener to this subscription

                    // THE HANDLER IS THE SAME FOR ALL SUBSCRIPTIONS OF THE SAME TYPE!
                    messageHandler = messageHandlers[i];

                    // register for publication
                    messageHandlerTypes = messageHandler.getHandledMessages();
//                    final int handlerSize = messageHandlerTypes.length;
//
//                    switch (handlerSize) {
//                        case 0: {
//                            handlerType = Void.class;
//
//                            // makes this subscription visible for publication
//                            final Subscription[] currentSubs = singleSubs.get(handlerType);
//                            final int currentLength = currentSubs.length;
//
//                            // add the new subscription to the array
//                            final Subscription[] newSubs = Arrays.copyOf(currentSubs, currentLength + 1, Subscription[].class);
//                            newSubs[currentLength] = subscription;
//                            singleSubs.put(handlerType, newSubs);
//                            break;
//                        }
//
//                        case 1: {
                            handlerType = messageHandlerTypes[0];

                            // makes this subscription visible for publication
                            final Subscription[] currentSubs = singleSubs.get(handlerType);
                            final int currentLength = currentSubs.length;

                            // add the new subscription to the array
                            final Subscription[] newSubs = Arrays.copyOf(currentSubs, currentLength + 1, Subscription[].class);
                            newSubs[currentLength] = subscription;
                            singleSubs.put(handlerType, newSubs);


                            // update the varity/super types
                            // registerExtraSubs(handlerType, singleSubs, localSuperSubs, localVaritySubs);

//                            break;
//                        }
//
//                        case 2: {
//                            final MultiClass multiClass = classTree.get(messageHandlerTypes[0],
//                                                                        messageHandlerTypes[1]);
//                            // makes this subscription visible for publication
//                            final Subscription[] currentSubs = multiSubs.get(multiClass);
//                            final int currentLength = currentSubs.length;
//
//                            // add the new subscription to the array
//                            final Subscription[] newSubs = Arrays.copyOf(currentSubs, currentLength + 1, Subscription[].class);
//                            newSubs[currentLength] = subscription;
//                            multiSubs.put(multiClass, newSubs);
//
//                            break;
//                        }
//
//                        case 3: {
//                            final MultiClass multiClass = classTree.get(messageHandlerTypes[0],
//                                                                        messageHandlerTypes[1],
//                                                                        messageHandlerTypes[2]);
//                            // makes this subscription visible for publication
//                            final Subscription[] currentSubs = multiSubs.get(multiClass);
//                            final int currentLength = currentSubs.length;
//
//                            // add the new subscription to the array
//                            final Subscription[] newSubs = Arrays.copyOf(currentSubs, currentLength + 1, Subscription[].class);
//                            newSubs[currentLength] = subscription;
//                            multiSubs.put(multiClass, newSubs);
//
//                            break;
//                        }
//
//                        default: {
//                            final MultiClass multiClass = classTree.get(messageHandlerTypes);
//                            // makes this subscription visible for publication
//                            final Subscription[] currentSubs = multiSubs.get(multiClass);
//                            final int currentLength = currentSubs.length;
//
//                            // add the new subscription to the array
//                            final Subscription[] newSubs = Arrays.copyOf(currentSubs, currentLength + 1, Subscription[].class);
//                            newSubs[currentLength] = subscription;
//                            multiSubs.put(multiClass, newSubs);
//
//                            break;
//                        }
//                    }
                }

                // save this snapshot back to the original (single writer principle)
                subsSingleREF.lazySet(this, singleSubs);
//                subsMultiREF.lazySet(this, multiSubs);
//            subsSuperSingleREF.lazySet(this, localSuperSubs);
//            subsVaritySingleREF.lazySet(this, localVaritySubs);
//            subsSuperVaritySingleREF.lazySet(this, localSuperVaritySubs);


                // only dump the super subscritions if it is a COMPLETELY NEW subscription. If it's not new, then the heirarchy isn't
                // changing for super subscriptions
                subsSuperSingleREF.lazySet(this, new IdentityMap(32));
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

    private
    void registerExtraSubs(final Class<?> clazz,
                           final IdentityMap<Class<?>, Subscription[]> subsPerMessageSingle,
                           final IdentityMap<Class<?>, Subscription[]> subsPerSuperMessageSingle,
                           final IdentityMap<Class<?>, Subscription[]> subsPerVarityMessageSingle) {

//        final Class<?> arrayVersion = this.classUtils.getArrayClass(clazz);  // never returns null, cached response
        final Class<?>[] superClasses = this.classUtils.getSuperClasses(clazz);  // never returns null, cached response

        Subscription sub;
//
//        // Register Varity (Var-Arg) subscriptions
//        final Subscription[] arraySubs = subsPerMessageSingle.get(arrayVersion);
//        if (arraySubs != null) {
//            final int length = arraySubs.length;
//            final ArrayList<Subscription> varArgSubsAsList = new ArrayList<Subscription>(length);
//
//            for (int i = 0; i < length; i++) {
//                sub = arraySubs[i];
//
//                if (sub.getHandler().acceptsVarArgs()) {
//                    varArgSubsAsList.add(sub);
//                }
//            }
//
//            if (!varArgSubsAsList.isEmpty()) {
//                subsPerVarityMessageSingle.put(clazz, varArgSubsAsList.toArray(new Subscription[0]));
//            }
//        }


//
//        // Register SuperClass subscriptions
//        final int length = superClasses.length;
//        final ArrayList<Subscription> subsAsList = new ArrayList<Subscription>(length);
//
//        // walks through all of the subscriptions that might exist for super types, and if applicable, save them
//        for (int i = 0; i < length; i++) {
//            final Class<?> superClass = superClasses[i];
//            final Subscription[]  superSubs = subsPerMessageSingle.get(superClass);
//
//            if (superSubs != null) {
//                int superSubLength = superSubs.length;
//                for (int j = 0; j < superSubLength; j++) {
//                    sub = superSubs[j];
//
//                    if (sub.getHandler().acceptsSubtypes()) {
//                        subsAsList.add(sub);
//                    }
//                }
//            }
//        }
//
//        if (!subsAsList.isEmpty()) {
//            // save the subscriptions
//            subsPerSuperMessageSingle.put(clazz, subsAsList.toArray(new Subscription[0]));
//        }
    }
















    public
    AtomicBoolean getVarArgPossibility() {
        return varArgPossibility;
    }

    public
    VarArgUtils getVarArgUtils() {
        return varArgUtils;
    }

    // can return null
    public
    Subscription[] getSubs(final Class<?> messageClass) {
        return (Subscription[]) subsSingleREF.get(this).get(messageClass);
    }

    // can return null
    public
    Subscription[] getSubs(final Class<?> messageClass1, final Class<?> messageClass2) {
        // never returns null
        final MultiClass multiClass = classTree.get(messageClass1,
                                                    messageClass2);
        return (Subscription[]) subsMultiREF.get(this).get(multiClass);
    }

    // can return null
    public
    Subscription[] getSubs(final Class<?> messageClass1, final Class<?> messageClass2, final Class<?> messageClass3) {
        // never returns null
        final MultiClass multiClass = classTree.get(messageClass1,
                                                    messageClass2,
                                                    messageClass3);
        return (Subscription[]) subsMultiREF.get(this).get(multiClass);
    }

    // can return null
    public
    Subscription[] getSubs(final Class<?>[] messageClasses) {
        // never returns null
        final MultiClass multiClass = classTree.get(messageClasses);

        return (Subscription[]) subsMultiREF.get(this).get(multiClass);
    }





    // can return null
    public
    Subscription[] getSuperSubs(final Class<?> messageClass) {
        final Class<?>[] superClasses = this.classUtils.getSuperClasses(messageClass);  // never returns null, cached response

        final int length = superClasses.length;
        final ArrayList<Subscription> subsAsList = new ArrayList<Subscription>(length);

        final IdentityMap<Class<?>, Subscription[]> localSubs = subsSingleREF.get(this);

        Class<?> superClass;
        Subscription sub;
        Subscription[] superSubs;
        boolean hasSubs = false;

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
                        hasSubs = true;
                    }
                }
            }
        }


        // subsAsList now contains ALL of the super-class subscriptions.
        if (hasSubs) {
            return subsAsList.toArray(new Subscription[0]);
        }
        else {
            // TODO: shortcut out if there are no handlers that accept subtypes
            return null;
        }


// IT IS CRITICAL TO REMEMBER: The subscriptions that are remembered here DO NOT CHANGE (only the listeners inside them change). if we
// subscribe a super/child class -- THEN these subscriptions change!

//        return (Subscription[]) subsSuperSingleREF.get(this).get(messageClass);
    }

    // can return null
    public
    Subscription[] getSuperSubs(final Class<?> messageClass1, final Class<?> messageClass2) {
        // save the subscriptions
        final Class<?>[] superClasses1 = this.classUtils.getSuperClasses(messageClass1);  // never returns null, cached response
        final Class<?>[] superClasses2 = this.classUtils.getSuperClasses(messageClass2);  // never returns null, cached response

        final IdentityMap<MultiClass, Subscription[]> localSubs = subsMultiREF.get(this);

        Class<?> superClass1;
        Class<?> superClass2;
        Subscription sub;
        Subscription[] superSubs;
        boolean hasSubs = false;


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
                final MultiClass multiClass = classTree.get(superClass1,
                                                            superClass2);
                superSubs = localSubs.get(multiClass);
                if (superSubs != null) {
                    for (int k = 0; k < superSubs.length; k++) {
                        sub = superSubs[k];

                        if (sub.getHandler().acceptsSubtypes()) {
                            subsAsList.add(sub);
                            hasSubs = true;
                        }
                    }
                }
            }
        }

        // subsAsList now contains ALL of the super-class subscriptions.
        if (hasSubs) {
            return subsAsList.toArray(new Subscription[0]);
        }
        else {
            // TODO: shortcut out if there are no handlers that accept subtypes
            return null;
        }
    }




    // can return null
    public
    Subscription[] getExactAndSuper(final Class<?> messageClass1, final Class<?> messageClass2) {
//        ArrayList<Subscription> collection = getSubs(messageClass1, messageClass2); // can return null
//
//        // now publish superClasses
//        final ArrayList<Subscription> superSubs = this.subUtils.getSuperSubscriptions(messageClass1, messageClass2,
//                                                                                      this); // NOT return null
//
//        if (collection != null) {
//            collection = new ArrayList<Subscription>(collection);
//
//            if (!superSubs.isEmpty()) {
//                collection.addAll(superSubs);
//            }
//        }
//        else if (!superSubs.isEmpty()) {
//            collection = superSubs;
//        }
//
//        if (collection != null) {
//            return collection.toArray(new Subscription[0]);
//        }
//        else {
            return null;
//        }
    }

    // can return null
    public
    Subscription[] getExactAndSuper(final Class<?> messageClass1, final Class<?> messageClass2, final Class<?> messageClass3) {
//
//        ArrayList<Subscription> collection = getExactAsArray(messageClass1, messageClass2, messageClass3); // can return null
//
//        // now publish superClasses
//        final ArrayList<Subscription> superSubs = this.subUtils.getSuperSubscriptions(messageClass1, messageClass2, messageClass3,
//                                                                                      this); // NOT return null
//
//        if (collection != null) {
//            collection = new ArrayList<Subscription>(collection);
//
//            if (!superSubs.isEmpty()) {
//                collection.addAll(superSubs);
//            }
//        }
//        else if (!superSubs.isEmpty()) {
//            collection = superSubs;
//        }
//
//        if (collection != null) {
//            return collection.toArray(new Subscription[0]);
//        }
//        else {
            return null;
//        }
    }


    public
    Subscription[] getSuperSubs(final Class<?> messageClass1, final Class<?> messageClass2, final Class<?> messageClass3) {
        return null;
    }
}
