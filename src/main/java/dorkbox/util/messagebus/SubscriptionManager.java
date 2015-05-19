package dorkbox.util.messagebus;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import dorkbox.util.messagebus.common.ConcurrentHashMapV8;
import dorkbox.util.messagebus.common.HashMapTree;
import dorkbox.util.messagebus.common.ISetEntry;
import dorkbox.util.messagebus.common.StrongConcurrentSet;
import dorkbox.util.messagebus.common.SubscriptionUtils;
import dorkbox.util.messagebus.common.VarArgPossibility;
import dorkbox.util.messagebus.common.VarArgUtils;
import dorkbox.util.messagebus.common.thread.ConcurrentSet;
import dorkbox.util.messagebus.common.thread.SubscriptionHolder;
import dorkbox.util.messagebus.listener.MessageHandler;
import dorkbox.util.messagebus.listener.MetadataReader;
import dorkbox.util.messagebus.subscription.Subscription;

/**
 * The subscription managers responsibility is to consistently handle and synchronize the message listener subscription process.
 * It provides fast lookup of existing subscriptions when another instance of an already known
 * listener is subscribed and takes care of creating new set of subscriptions for any unknown class that defines
 * message handlers.
 *
 *
 * Subscribe/Unsubscribe, while it is possible for them to be 100% concurrent (in relation to listeners per subscription),
 * getting an accurate reflection of the number of subscriptions, or guaranteeing a "HAPPENS-BEFORE" relationship really
 * complicates this, so it has been modified for subscribe/unsubscibe to be mutually exclusive.
 *
 * Given these restrictions and complexity, it is much easier to create a MPSC blocking queue, and have a single thread
 * manage sub/unsub.
 *
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class SubscriptionManager {
    private static final float LOAD_FACTOR = 0.8F;

    // the metadata reader that is used to inspect objects passed to the subscribe method
    private static final MetadataReader metadataReader = new MetadataReader();

    final SubscriptionUtils utils;

    // remember already processed classes that do not contain any message handlers
    private final Map<Class<?>, Boolean> nonListeners;

    // shortcut publication if we know there is no possibility of varArg (ie: a method that has an array as arguments)
    private final VarArgPossibility varArgPossibility = new VarArgPossibility();

    // all subscriptions per message type. We perpetually KEEP the types, as this lowers the amount of locking required
    // this is the primary list for dispatching a specific message
    // write access is synchronized and happens only when a listener of a specific class is registered the first time
    private final ConcurrentMap<Class<?>, ConcurrentSet<Subscription>> subscriptionsPerMessageSingle;
    private final HashMapTree<Class<?>, ConcurrentSet<Subscription>> subscriptionsPerMessageMulti;

    // all subscriptions per messageHandler type
    // this map provides fast access for subscribing and unsubscribing
    // write access is synchronized and happens very infrequently
    // once a collection of subscriptions is stored it does not change
    private final ConcurrentMap<Class<?>, ConcurrentSet<Subscription>> subscriptionsPerListener;


    // superClassSubscriptions keeps track of all subscriptions of super classes. SUB/UNSUB dumps it, so it is recreated dynamically.
    // it's a hit on SUB/UNSUB, but REALLY improves performance on handlers
    // it's faster to create a new one for SUB/UNSUB than it is to clear() on the original one
    private final ConcurrentMap<Class<?>, ConcurrentSet<Subscription>> superClassSubscriptions;
    private final HashMapTree<Class<?>, ConcurrentSet<Subscription>> superClassSubscriptionsMulti;

    private final VarArgUtils varArgUtils;

    // stripe size of maps for concurrency
    private final int STRIPE_SIZE;

    private final SubscriptionHolder subHolderSingle;
    private final SubscriptionHolder subHolderConcurrent;

    SubscriptionManager(int numberOfThreads) {
        this.STRIPE_SIZE = numberOfThreads;

        this.utils = new SubscriptionUtils(LOAD_FACTOR, numberOfThreads);

        // modified ONLY during SUB/UNSUB
        {
            this.nonListeners = new ConcurrentHashMapV8<Class<?>, Boolean>(4, SubscriptionManager.LOAD_FACTOR);

            this.subscriptionsPerMessageSingle = new ConcurrentHashMapV8<Class<?>, ConcurrentSet<Subscription>>(32, SubscriptionManager.LOAD_FACTOR, 1);
            this.subscriptionsPerMessageMulti = new HashMapTree<Class<?>, ConcurrentSet<Subscription>>(4, SubscriptionManager.LOAD_FACTOR);

            // only used during SUB/UNSUB
            this.subscriptionsPerListener = new ConcurrentHashMapV8<Class<?>, ConcurrentSet<Subscription>>(32, SubscriptionManager.LOAD_FACTOR, 1);
        }

        // modified by N threads
        {
            // superClassSubscriptions keeps track of all subscriptions of super classes. SUB/UNSUB dumps it, so it is recreated dynamically.
            // it's a hit on SUB/UNSUB, but improves performance of handlers
            this.superClassSubscriptions = new ConcurrentHashMapV8<Class<?>, ConcurrentSet<Subscription>>(32, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);
            this.superClassSubscriptionsMulti = new HashMapTree<Class<?>, ConcurrentSet<Subscription>>(4, SubscriptionManager.LOAD_FACTOR);

            // var arg subscriptions keep track of which subscriptions can handle varArgs. SUB/UNSUB dumps it, so it is recreated dynamically.
            // it's a hit on SUB/UNSUB, but improves performance of handlers
            this.varArgUtils = new VarArgUtils(this.utils, this.subscriptionsPerMessageSingle, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);
        }

        this.subHolderSingle = new SubscriptionHolder(SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);
        this.subHolderConcurrent = new SubscriptionHolder(SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);
    }

    public void shutdown() {
        this.nonListeners.clear();

        this.subscriptionsPerMessageSingle.clear();
        this.subscriptionsPerMessageMulti.clear();
        this.subscriptionsPerListener.clear();

        this.utils.shutdown();
        clearConcurrentCollections();
    }

    public boolean hasVarArgPossibility() {
        return this.varArgPossibility.get();
    }

    public void subscribe(Object listener) {
        if (listener == null) {
            return;
        }

        Class<?> listenerClass = listener.getClass();

        if (this.nonListeners.containsKey(listenerClass)) {
            // early reject of known classes that do not define message handlers
            return;
        }

        // these are concurrent collections
        clearConcurrentCollections();


        // no point in locking everything. We lock on the class object being subscribed, since that is as coarse as we can go.
        // the listenerClass is GUARANTEED to be unique and the same object, per classloader. We do NOT LOCK for visibility,
        // but for concurrency because there are race conditions here if we don't.
        synchronized(listenerClass) {
            ConcurrentMap<Class<?>, ConcurrentSet<Subscription>> subsPerListener2 = this.subscriptionsPerListener;
            ConcurrentSet<Subscription> subsPerListener = subsPerListener2.get(listenerClass);
            if (subsPerListener == null) {
                // a listener is subscribed for the first time
                StrongConcurrentSet<MessageHandler> messageHandlers = SubscriptionManager.metadataReader.getMessageListener(listenerClass).getHandlers();
                int handlersSize = messageHandlers.size();

                if (handlersSize == 0) {
                    // remember the class as non listening class if no handlers are found
                    this.nonListeners.put(listenerClass, Boolean.TRUE);
                    return;
                } else {
                    VarArgPossibility varArgPossibility = this.varArgPossibility;

                    subsPerListener = new ConcurrentSet<Subscription>(16, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);
                    ConcurrentMap<Class<?>, ConcurrentSet<Subscription>> subsPerMessageSingle = this.subscriptionsPerMessageSingle;

                    ISetEntry<MessageHandler> current = messageHandlers.head;
                    MessageHandler messageHandler;
                    while (current != null) {
                        messageHandler = current.getValue();
                        current = current.next();

                        ConcurrentSet<Subscription> subsPerType = null;

                        // now add this subscription to each of the handled types
                        Class<?>[] types = messageHandler.getHandledMessages();
                        int size = types.length;

                        switch (size) {
                            case 1: {
                                SubscriptionHolder subHolderConcurrent = this.subHolderConcurrent;
                                subsPerType = subHolderConcurrent.get();

                                ConcurrentSet<Subscription> putIfAbsent = subsPerMessageSingle.putIfAbsent(types[0], subsPerType);
                                if (putIfAbsent != null) {
                                    subsPerType = putIfAbsent;
                                } else {
                                    subHolderConcurrent.set(subHolderConcurrent.initialValue());
                                    boolean isArray = this.utils.isArray(types[0]);
                                    this.utils.getSuperClasses(types[0], isArray);
                                    if (isArray) {
                                        varArgPossibility.set(true);
                                    }
                                }
                                break;
                            }
                            case 2: {
                                // the HashMapTree uses read/write locks, so it is only accessible one thread at a time
                                SubscriptionHolder subHolderSingle = this.subHolderSingle;
                                subsPerType = subHolderSingle.get();

                                ConcurrentSet<Subscription> putIfAbsent = this.subscriptionsPerMessageMulti.putIfAbsent(subsPerType, types[0], types[1]);
                                if (putIfAbsent != null) {
                                    subsPerType = putIfAbsent;
                                } else {
                                    subHolderSingle.set(subHolderSingle.initialValue());
                                    this.utils.getSuperClasses(types[0]);
                                    this.utils.getSuperClasses(types[1]);
                                }
                                break;
                            }
                            case 3: {
                                // the HashMapTree uses read/write locks, so it is only accessible one thread at a time
                                SubscriptionHolder subHolderSingle = this.subHolderSingle;
                                subsPerType = subHolderSingle.get();

                                ConcurrentSet<Subscription> putIfAbsent = this.subscriptionsPerMessageMulti.putIfAbsent(subsPerType, types[0], types[1], types[2]);
                                if (putIfAbsent != null) {
                                    subsPerType = putIfAbsent;
                                } else {
                                    subHolderSingle.set(subHolderSingle.initialValue());
                                    this.utils.getSuperClasses(types[0]);
                                    this.utils.getSuperClasses(types[1]);
                                    this.utils.getSuperClasses(types[2]);
                                }
                                break;
                            }
                            default: {
                                // the HashMapTree uses read/write locks, so it is only accessible one thread at a time
                                SubscriptionHolder subHolderSingle = this.subHolderSingle;
                                subsPerType = subHolderSingle.get();

                                ConcurrentSet<Subscription> putIfAbsent = this.subscriptionsPerMessageMulti.putIfAbsent(subsPerType, types);
                                if (putIfAbsent != null) {
                                    subsPerType = putIfAbsent;
                                } else {
                                    subHolderSingle.set(subHolderSingle.initialValue());

                                    Class<?> c;
                                    int length = types.length;
                                    for (int i = 0; i < length; i++) {
                                        c = types[i];
                                        this.utils.getSuperClasses(c);
                                    }
                                }
                                break;
                            }
                        }

                        // create the subscription
                        Subscription subscription = new Subscription(messageHandler);
                        subscription.subscribe(listener);

                        subsPerListener.add(subscription); // activates this sub for sub/unsub
                        subsPerType.add(subscription);  // activates this sub for publication
                    }

                    subsPerListener2.put(listenerClass, subsPerListener);
                }
            } else {
                // subscriptions already exist and must only be updated
                for (Subscription sub : subsPerListener) {
                    sub.subscribe(listener);
                }
            }
        }
    }

    public final void unsubscribe(Object listener) {
        if (listener == null) {
            return;
        }

        Class<?> listenerClass = listener.getClass();
        if (this.nonListeners.containsKey(listenerClass)) {
            // early reject of known classes that do not define message handlers
            return;
        }

        // these are concurrent collections
        clearConcurrentCollections();

        synchronized(listenerClass) {
            ConcurrentSet<Subscription> subscriptions = this.subscriptionsPerListener.get(listenerClass);
            if (subscriptions != null) {
                for (Subscription sub : subscriptions) {
                    sub.unsubscribe(listener);
                }
            }
        }
    }

    private void clearConcurrentCollections() {
        this.superClassSubscriptions.clear();
        this.varArgUtils.clear();
    }

    // CAN RETURN NULL
    public final ConcurrentSet<Subscription> getSubscriptionsByMessageType(Class<?> messageType) {
        return this.subscriptionsPerMessageSingle.get(messageType);
    }

    // CAN RETURN NULL
    public final ConcurrentSet<Subscription> getSubscriptionsByMessageType(Class<?> messageType1, Class<?> messageType2) {
        return this.subscriptionsPerMessageMulti.get(messageType1, messageType2);
    }


    // CAN RETURN NULL
    public final ConcurrentSet<Subscription> getSubscriptionsByMessageType(Class<?> messageType1, Class<?> messageType2, Class<?> messageType3) {
        return this.subscriptionsPerMessageMulti.getValue(messageType1, messageType2, messageType3);
    }

    // CAN RETURN NULL
    public final Collection<Subscription> getSubscriptionsByMessageType(Class<?>... messageTypes) {
        return this.subscriptionsPerMessageMulti.get(messageTypes);
    }

    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public ConcurrentSet<Subscription> getVarArgSubscriptions(Class<?> messageClass) {
        return this.varArgUtils.getVarArgSubscriptions(messageClass);
    }

    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public ConcurrentSet<Subscription> getVarArgSuperSubscriptions(Class<?> messageClass) {
        return this.varArgUtils.getVarArgSuperSubscriptions(messageClass);
    }

    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public ConcurrentSet<Subscription> getVarArgSuperSubscriptions(Class<?> messageClass1, Class<?> messageClass2) {
        return this.varArgUtils.getVarArgSuperSubscriptions(messageClass1, messageClass2);
    }

    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public ConcurrentSet<Subscription> getVarArgSuperSubscriptions(final Class<?> messageClass1, final Class<?> messageClass2, final Class<?> messageClass3) {
        return this.varArgUtils.getVarArgSuperSubscriptions(messageClass1, messageClass2, messageClass3);
    }


    // CAN NOT RETURN NULL
    // ALSO checks to see if the superClass accepts subtypes.
    public final ConcurrentSet<Subscription> getSuperSubscriptions(Class<?> superType) {
        // whenever our subscriptions change, this map is cleared.
        ConcurrentMap<Class<?>, ConcurrentSet<Subscription>> local = this.superClassSubscriptions;

        SubscriptionHolder subHolderConcurrent = this.subHolderConcurrent;
        ConcurrentSet<Subscription> subsPerType = subHolderConcurrent.get();

        // cache our subscriptions for super classes, so that their access can be fast!
        ConcurrentSet<Subscription> putIfAbsent = local.putIfAbsent(superType, subsPerType);
        if (putIfAbsent == null) {
            // we are the first one in the map
            subHolderConcurrent.set(subHolderConcurrent.initialValue());

            StrongConcurrentSet<Class<?>> types = this.utils.getSuperClasses(superType);
            if (types.isEmpty()) {
                return subsPerType;
            }

            Map<Class<?>, ConcurrentSet<Subscription>> local2 = this.subscriptionsPerMessageSingle;

            ISetEntry<Class<?>> current1 = null;
            Class<?> superClass;

            current1 = types.head;
            while (current1 != null) {
                superClass = current1.getValue();
                current1 = current1.next();

                ConcurrentSet<Subscription> subs = local2.get(superClass);
                if (subs != null) {
                    for (Subscription sub : subs) {
                        if (sub.acceptsSubtypes()) {
                            subsPerType.add(sub);
                        }
                    }
                }
            }
            return subsPerType;
        } else {
            // someone beat us
            return putIfAbsent;
        }

    }

    // CAN NOT RETURN NULL
    // ALSO checks to see if the superClass accepts subtypes.
    public ConcurrentSet<Subscription> getSuperSubscriptions(Class<?> superType1, Class<?> superType2) {
        HashMapTree<Class<?>, ConcurrentSet<Subscription>> local = this.superClassSubscriptionsMulti;

        // whenever our subscriptions change, this map is cleared.
        HashMapTree<Class<?>, ConcurrentSet<Subscription>> subsPerTypeLeaf = local.getLeaf(superType1, superType2);
        ConcurrentSet<Subscription> subsPerType = null;

        // we DO NOT care about duplicate, because the answers will be the same
        if (subsPerTypeLeaf != null) {
            // if the leaf exists, then the value exists.
            subsPerType = subsPerTypeLeaf.getValue();
        } else {
            SubscriptionHolder subHolderSingle = this.subHolderSingle;
            subsPerType = subHolderSingle.get();

            // cache our subscriptions for super classes, so that their access can be fast!
            ConcurrentSet<Subscription> putIfAbsent = local.putIfAbsent(subsPerType, superType1, superType2);
            if (putIfAbsent == null) {
                // we are the first one in the map
                subHolderSingle.set(subHolderSingle.initialValue());

                // whenever our subscriptions change, this map is cleared.
                StrongConcurrentSet<Class<?>> types1 = this.utils.getSuperClasses(superType1);
                StrongConcurrentSet<Class<?>> types2 = this.utils.getSuperClasses(superType2);

                ConcurrentSet<Subscription> subs;
                HashMapTree<Class<?>, ConcurrentSet<Subscription>> leaf1;
                HashMapTree<Class<?>, ConcurrentSet<Subscription>> leaf2;

                ISetEntry<Class<?>> current1 = null;
                Class<?> eventSuperType1;

                ISetEntry<Class<?>> current2 = null;
                Class<?> eventSuperType2;

                if (types1 != null) {
                    current1 = types1.head;
                }
                while (current1 != null) {
                    eventSuperType1 = current1.getValue();
                    current1 = current1.next();

                    boolean type1Matches = eventSuperType1 == superType1;
                    if (type1Matches) {
                        continue;
                    }

                    leaf1 = this.subscriptionsPerMessageMulti.getLeaf(eventSuperType1);
                    if (leaf1 != null) {
                        if (types2 != null) {
                            current2 = types2.head;
                        }
                        while (current2 != null) {
                            eventSuperType2 = current2.getValue();
                            current2 = current2.next();

                            if (type1Matches && eventSuperType2 == superType2) {
                                continue;
                            }

                            leaf2 = leaf1.getLeaf(eventSuperType2);

                            if (leaf2 != null) {
                                subs = leaf2.getValue();
                                if (subs != null) {
                                    for (Subscription sub : subs) {
                                        if (sub.acceptsSubtypes()) {
                                            subsPerType.add(sub);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // someone beat us
                subsPerType = putIfAbsent;
            }
        }

        return subsPerType;
    }

    // CAN NOT RETURN NULL
    // ALSO checks to see if the superClass accepts subtypes.
    public ConcurrentSet<Subscription> getSuperSubscriptions(Class<?> superType1, Class<?> superType2, Class<?> superType3) {
        HashMapTree<Class<?>, ConcurrentSet<Subscription>> local = this.superClassSubscriptionsMulti;

        // whenever our subscriptions change, this map is cleared.
        HashMapTree<Class<?>, ConcurrentSet<Subscription>> subsPerTypeLeaf = local.getLeaf(superType1, superType2, superType3);
        ConcurrentSet<Subscription> subsPerType;


        // we DO NOT care about duplicate, because the answers will be the same
        if (subsPerTypeLeaf != null) {
            // if the leaf exists, then the value exists.
            subsPerType = subsPerTypeLeaf.getValue();
        } else {
            SubscriptionHolder subHolderSingle = this.subHolderSingle;
            subsPerType = subHolderSingle.get();

            // cache our subscriptions for super classes, so that their access can be fast!
            ConcurrentSet<Subscription> putIfAbsent = local.putIfAbsent(subsPerType, superType1, superType2, superType3);
            if (putIfAbsent == null) {
                // we are the first one in the map
                subHolderSingle.set(subHolderSingle.initialValue());

                StrongConcurrentSet<Class<?>> types1 = this.utils.getSuperClasses(superType1);
                StrongConcurrentSet<Class<?>> types2 = this.utils.getSuperClasses(superType2);
                StrongConcurrentSet<Class<?>> types3 = this.utils.getSuperClasses(superType3);

                ConcurrentSet<Subscription> subs;
                HashMapTree<Class<?>, ConcurrentSet<Subscription>> leaf1;
                HashMapTree<Class<?>, ConcurrentSet<Subscription>> leaf2;
                HashMapTree<Class<?>, ConcurrentSet<Subscription>> leaf3;

                ISetEntry<Class<?>> current1 = null;
                Class<?> eventSuperType1;

                ISetEntry<Class<?>> current2 = null;
                Class<?> eventSuperType2;

                ISetEntry<Class<?>> current3 = null;
                Class<?> eventSuperType3;

                if (types1 != null) {
                    current1 = types1.head;
                }
                while (current1 != null) {
                    eventSuperType1 = current1.getValue();
                    current1 = current1.next();

                    boolean type1Matches = eventSuperType1 == superType1;
                    if (type1Matches) {
                        continue;
                    }

                    leaf1 = this.subscriptionsPerMessageMulti.getLeaf(eventSuperType1);
                    if (leaf1 != null) {
                        if (types2 != null) {
                            current2 = types2.head;
                        }
                        while (current2 != null) {
                            eventSuperType2 = current2.getValue();
                            current2 = current2.next();

                            boolean type12Matches = type1Matches && eventSuperType2 == superType2;
                            if (type12Matches) {
                                continue;
                            }

                            leaf2 = leaf1.getLeaf(eventSuperType2);

                            if (leaf2 != null) {
                                if (types3 != null) {
                                    current3 = types3.head;
                                }
                                while (current3 != null) {
                                    eventSuperType3 = current3.getValue();
                                    current3 = current3.next();

                                    if (type12Matches && eventSuperType3 == superType3) {
                                        continue;
                                    }

                                    leaf3 = leaf2.getLeaf(eventSuperType3);

                                    if (leaf3 != null) {
                                        subs = leaf3.getValue();
                                        if (subs != null) {
                                            for (Subscription sub : subs) {
                                                if (sub.acceptsSubtypes()) {
                                                    subsPerType.add(sub);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // someone beat us
                subsPerType = putIfAbsent;
            }
        }

        return subsPerType;
    }
}
