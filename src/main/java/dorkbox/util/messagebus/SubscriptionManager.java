package dorkbox.util.messagebus;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import dorkbox.util.messagebus.common.ConcurrentHashMapV8;
import dorkbox.util.messagebus.common.HashMapTree;
import dorkbox.util.messagebus.common.ReflectionUtils;
import dorkbox.util.messagebus.common.StrongConcurrentSet;
import dorkbox.util.messagebus.common.StrongConcurrentSetV8;
import dorkbox.util.messagebus.common.SubscriptionHolder;
import dorkbox.util.messagebus.common.SuperClassIterator;
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
 * @author bennidi
 *         Date: 5/11/13
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class SubscriptionManager {
    private static final float LOAD_FACTOR = 0.8F;

    // this keeps us from having to constantly recheck our cache for subscriptions
    private static final Collection<Subscription> EMPTY_SUBS = Collections.emptyList();

    // the metadata reader that is used to inspect objects passed to the subscribe method
    private static final MetadataReader metadataReader = new MetadataReader();

    // remember already processed classes that do not contain any message handlers
    private final Map<Class<?>, Boolean> nonListeners;

    // all subscriptions per message type. We perpetually KEEP the types, as this lowers the amount of locking required
    // this is the primary list for dispatching a specific message
    // write access is synchronized and happens only when a listener of a specific class is registered the first time
    private final ConcurrentMap<Class<?>, Collection<Subscription>> subscriptionsPerMessageSingle;
    private final HashMapTree<Class<?>, Collection<Subscription>> subscriptionsPerMessageMulti;

    // all subscriptions per messageHandler type
    // this map provides fast access for subscribing and unsubscribing
    // write access is synchronized and happens very infrequently
    // once a collection of subscriptions is stored it does not change
    private final ConcurrentMap<Class<?>, Collection<Subscription>> subscriptionsPerListener;

    private final Map<Class<?>, Class<?>> arrayVersionCache;
    private final Map<Class<?>, Collection<Class<?>>> superClassesCache;

    // superClassSubscriptions keeps track of all subscriptions of super classes. SUB/UNSUB dumps it, so it is recreated dynamically.
    // it's a hit on SUB/UNSUB, but REALLY improves performance on handlers
    // it's faster to create a new one for SUB/UNSUB than it is to clear() on the original one
    private final Map<Class<?>, Collection<Subscription>> superClassSubscriptions;
    private final HashMapTree<Class<?>, Collection<Subscription>> superClassSubscriptionsMulti;

    private final Map<Class<?>, Collection<Subscription>> varArgSubscriptions;
    private final Map<Class<?>, Collection<Subscription>> varArgSuperClassSubscriptions;

    // to keep track if we really need to clear our maps
    private final AtomicBoolean superCheck = new AtomicBoolean();

    // stripe size of maps for concurrency
    private final int STRIPE_SIZE;

    private final SubscriptionHolder subHolder;
    private final SubscriptionHolder subHolderConcurrent;


    SubscriptionManager(int numberOfThreads) {
        this.STRIPE_SIZE = numberOfThreads;

        // modified ONLY during SUB/UNSUB
        {
            this.nonListeners = new ConcurrentHashMapV8<Class<?>, Boolean>(4, SubscriptionManager.LOAD_FACTOR);

            this.subscriptionsPerMessageSingle = new ConcurrentHashMapV8<Class<?>, Collection<Subscription>>(64, SubscriptionManager.LOAD_FACTOR, 1);
            this.subscriptionsPerMessageMulti = new HashMapTree<Class<?>, Collection<Subscription>>(4, SubscriptionManager.LOAD_FACTOR);

            // only used during SUB/UNSUB
            this.subscriptionsPerListener = new ConcurrentHashMapV8<Class<?>, Collection<Subscription>>(64, SubscriptionManager.LOAD_FACTOR, 1);
        }

        // modified by N threads
        {
            this.arrayVersionCache = new ConcurrentHashMapV8<Class<?>, Class<?>>(64, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);
            this.superClassesCache = new ConcurrentHashMapV8<Class<?>, Collection<Class<?>>>(64, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);

            // superClassSubscriptions keeps track of all subscriptions of super classes. SUB/UNSUB dumps it, so it is recreated dynamically.
            // it's a hit on SUB/UNSUB, but improves performance of handlers
            this.superClassSubscriptions = new ConcurrentHashMapV8<Class<?>, Collection<Subscription>>(64, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);
            this.superClassSubscriptionsMulti = new HashMapTree<Class<?>, Collection<Subscription>>(4, SubscriptionManager.LOAD_FACTOR);

            // var arg subscriptions keep track of which subscriptions can handle varArgs. SUB/UNSUB dumps it, so it is recreated dynamically.
            // it's a hit on SUB/UNSUB, but improves performance of handlers
            this.varArgSubscriptions = new ConcurrentHashMapV8<Class<?>, Collection<Subscription>>(64, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);
            this.varArgSuperClassSubscriptions = new ConcurrentHashMapV8<Class<?>, Collection<Subscription>>(64, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);
        }

        this.subHolder = new SubscriptionHolder(SubscriptionManager.LOAD_FACTOR, 1);
        this.subHolderConcurrent = new SubscriptionHolder(SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);
    }

    public void shutdown() {
        this.nonListeners.clear();

        this.subscriptionsPerMessageSingle.clear();
        this.subscriptionsPerMessageMulti.clear();
        this.subscriptionsPerListener.clear();

        this.superClassesCache.clear();
        this.superClassSubscriptions.clear();

        this.arrayVersionCache.clear();
        this.varArgSubscriptions.clear();
        this.varArgSuperClassSubscriptions.clear();
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
        boolean compareAndSet = this.superCheck.compareAndSet(true, false);
        if (compareAndSet) {
            this.superClassSubscriptions.clear();
            this.varArgSubscriptions.clear();
            this.varArgSuperClassSubscriptions.clear();
        }


        // no point in locking everything. We lock on the class object being subscribed, since that is as coarse as we can go.
        // the listenerClass is GUARANTEED to be unique and the same object, per classloader. We do NOT LOCK for visibility, but for concurrency
        synchronized(listenerClass) {
            ConcurrentMap<Class<?>, Collection<Subscription>> subsPerListener2 = this.subscriptionsPerListener;
            Collection<Subscription> subsPerListener = subsPerListener2.get(listenerClass);
            if (subsPerListener == null) {
                // a listener is subscribed for the first time
                Collection<MessageHandler> messageHandlers = SubscriptionManager.metadataReader.getMessageListener(listenerClass).getHandlers();
                int handlersSize = messageHandlers.size();

                if (handlersSize == 0) {
                    // remember the class as non listening class if no handlers are found
                    this.nonListeners.put(listenerClass, Boolean.TRUE);
                    return;
                } else {
                    // really was null
                    subsPerListener = new StrongConcurrentSetV8<Subscription>(16, SubscriptionManager.LOAD_FACTOR, 1);
                    ConcurrentMap<Class<?>, Collection<Subscription>> subsPerMessageSingle = this.subscriptionsPerMessageSingle;


                    for (MessageHandler messageHandler : messageHandlers) {
                        Collection<Subscription> subsPerType = null;

                        // now add this subscription to each of the handled types
                        Class<?>[] types = messageHandler.getHandledMessages();
                        int size = types.length;
                        switch (size) {
                            case 1: {
                                Collection<Subscription> putIfAbsent = subsPerMessageSingle.putIfAbsent(types[0], this.subHolderConcurrent.get());
                                if (putIfAbsent != null) {
                                    subsPerType = putIfAbsent;
                                } else {
                                    subsPerType = this.subHolderConcurrent.get();
                                    this.subHolderConcurrent.set(new StrongConcurrentSetV8<Subscription>(16, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE));
                                    getSuperClass(types[0]);
                                }
                                break;
                            }
                            case 2: {
                                Collection<Subscription> putIfAbsent = this.subscriptionsPerMessageMulti.putIfAbsent(this.subHolder.get(), types[0], types[1]);
                                if (putIfAbsent != null) {
                                    subsPerType = putIfAbsent;
                                } else {
                                    subsPerType = this.subHolder.get();
                                    this.subHolder.set(new StrongConcurrentSetV8<Subscription>(16, SubscriptionManager.LOAD_FACTOR, 1));
                                    getSuperClass(types[0]);
                                    getSuperClass(types[1]);
                                }
                                break;
                            }
                            case 3: {
                                Collection<Subscription> putIfAbsent = this.subscriptionsPerMessageMulti.putIfAbsent(this.subHolder.get(), types[0], types[1], types[2]);
                                if (putIfAbsent != null) {
                                    subsPerType = putIfAbsent;
                                } else {
                                    subsPerType = this.subHolder.get();
                                    this.subHolder.set(new StrongConcurrentSetV8<Subscription>(16, SubscriptionManager.LOAD_FACTOR, 1));
                                    getSuperClass(types[0]);
                                    getSuperClass(types[1]);
                                    getSuperClass(types[2]);
                                }
                                break;
                            }
                            default: {
                                Collection<Subscription> putIfAbsent = this.subscriptionsPerMessageMulti.putIfAbsent(this.subHolder.get(), types);
                                if (putIfAbsent != null) {
                                    subsPerType = putIfAbsent;
                                } else {
                                    subsPerType = this.subHolder.get();
                                    this.subHolder.set(new StrongConcurrentSetV8<Subscription>(16, SubscriptionManager.LOAD_FACTOR, 1));
                                    for (Class<?> c : types) {
                                        getSuperClass(c);
                                    }
                                }
                                break;
                            }
                        }

                        // create the subscription
                        Subscription subscription = new Subscription(messageHandler);
                        subscription.subscribe(listener);
                        subsPerListener.add(subscription);
                        subsPerType.add(subscription);
                    }

                    subsPerListener2.put(listenerClass, subsPerListener);
                }
            } else {
                // subscriptions already exist and must only be updated
                for (Subscription subscription : subsPerListener) {
                    subscription.subscribe(listener);
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

        boolean compareAndSet = this.superCheck.compareAndSet(true, false);
        if (compareAndSet) {
            this.superClassSubscriptions.clear();
            this.varArgSubscriptions.clear();
            this.varArgSuperClassSubscriptions.clear();
        }

        // no point in locking everything. We lock on the class object being subscribed, since that is as coarse as we can go.
        // the listenerClass is GUARANTEED to be unique and the same object, per classloader. We do NOT LOCK for visibility, but for concurrency
        synchronized(listenerClass) {
            Collection<Subscription> subscriptions = this.subscriptionsPerListener.get(listenerClass);
            if (subscriptions != null) {
                for (Subscription subscription : subscriptions) {
                    subscription.unsubscribe(listener);
                }
            }
        }
    }

    /**
     * race conditions will result in duplicate answers, which we don't care if happens
     * never returns null
     * never reset, since it never needs to be reset (as the class hierarchy doesn't change at runtime)
     */
    private Collection<Class<?>> getSuperClass(Class<?> clazz) {
        // this is never reset, since it never needs to be.
        Map<Class<?>, Collection<Class<?>>> local = this.superClassesCache;

        Collection<Class<?>> superTypes = local.get(clazz);
        if (superTypes == null) {
            boolean isArray = clazz.isArray();

            // it doesn't matter if concurrent access stomps on values, since they are always the same.
            superTypes = ReflectionUtils.getSuperTypes(clazz);
            StrongConcurrentSet<Class<?>> set = new StrongConcurrentSetV8<Class<?>>(superTypes.size() + 1, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);

            for (Class<?> c : superTypes) {
                if (isArray) {
                    c = getArrayClass(c);
                }
                set.add(c);
            }

            // race conditions will result in duplicate answers, which we don't care if happens
            local.put(clazz, set);
        }

        return superTypes;
    }

    /**
     * race conditions will result in duplicate answers, which we don't care if happens
     * never returns null
     * never reset
     */
    private Class<?> getArrayClass(Class<?> c) {
        Class<?> clazz = this.arrayVersionCache.get(c);
        if (clazz == null) {
            // messy, but the ONLY way to do it. Array super types are also arrays
            Object[] newInstance = (Object[]) Array.newInstance(c, 1);
            clazz = newInstance.getClass();
            this.arrayVersionCache.put(c, clazz);
        }

        return clazz;
    }


    // CAN RETURN NULL
    public final Collection<Subscription> getSubscriptionsByMessageType(Class<?> messageType) {
        return this.subscriptionsPerMessageSingle.get(messageType);
    }

    // CAN RETURN NULL
    public final Collection<Subscription> getSubscriptionsByMessageType(Class<?> messageType1, Class<?> messageType2) {
        return this.subscriptionsPerMessageMulti.get(messageType1, messageType2);
    }


    // CAN RETURN NULL
    public final Collection<Subscription> getSubscriptionsByMessageType(Class<?> messageType1, Class<?> messageType2, Class<?> messageType3) {
        return this.subscriptionsPerMessageMulti.getValue(messageType1, messageType2, messageType3);
    }

    // CAN RETURN NULL
    public final Collection<Subscription> getSubscriptionsByMessageType(Class<?>... messageTypes) {
        return this.subscriptionsPerMessageMulti.get(messageTypes);
    }

    // CAN RETURN NULL
    // check to see if the messageType can convert/publish to the "array" version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public Collection<Subscription> getVarArgSubscriptions(Class<?> varArgType) {
        this.superCheck.set(true);
        Map<Class<?>, Collection<Subscription>> local = this.varArgSubscriptions;

        // whenever our subscriptions change, this map is cleared.
        Collection<Subscription> subsPerType = local.get(varArgType);

        if (subsPerType == null) {
            // this caches our array type. This is never cleared.
            Class<?> arrayVersion = getArrayClass(varArgType);

            Map<Class<?>, Collection<Subscription>> local2 = this.subscriptionsPerMessageSingle;
            subsPerType = new StrongConcurrentSetV8<Subscription>(local2.size(), SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);

            Collection<Subscription> subs = local2.get(arrayVersion);
            if (subs != null && !subs.isEmpty()) {
                for (Subscription sub : subs) {
                    if (sub.acceptsVarArgs()) {
                        subsPerType.add(sub);
                    }
                }
            }

            // cache our subscriptions for super classes, so that their access can be fast!
            // duplicates are OK.
            local.put(varArgType, subsPerType);
        }

        return subsPerType;
    }

    // CAN RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public Collection<Subscription> getVarArgSuperSubscriptions(Class<?> varArgType) {
        this.superCheck.set(true);
        Map<Class<?>, Collection<Subscription>> local = this.varArgSuperClassSubscriptions;

        // whenever our subscriptions change, this map is cleared.
        Collection<Subscription> subsPerType = local.get(varArgType);

        if (subsPerType == null) {
            // this caches our array type. This is never cleared.
            Class<?> arrayVersion = getArrayClass(varArgType);
            Collection<Class<?>> types = getSuperClass(arrayVersion);
            if (types.isEmpty()) {
                local.put(varArgType, EMPTY_SUBS);
                return null;
            }

            Map<Class<?>, Collection<Subscription>> local2 = this.subscriptionsPerMessageSingle;
            subsPerType = new StrongConcurrentSetV8<Subscription>(local2.size(), SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);


            Iterator<Class<?>> iterator = types.iterator();
            while (iterator.hasNext()) {
                Class<?> superClass = iterator.next();

                Collection<Subscription> subs = local2.get(superClass);
                if (subs != null && !subs.isEmpty()) {
                    for (Subscription sub : subs) {
                        if (sub.acceptsSubtypes() && sub.acceptsVarArgs()) {
                            subsPerType.add(sub);
                        }
                    }
                }
            }

            // cache our subscriptions for super classes, so that their access can be fast!
            // duplicates are OK.
            local.put(varArgType, subsPerType);
        }

        return subsPerType;
    }


    // ALSO checks to see if the superClass accepts subtypes.
    public final Collection<Subscription> getSuperSubscriptions(Class<?> superType) {
        this.superCheck.set(true);
        Map<Class<?>, Collection<Subscription>> local = this.superClassSubscriptions;

        // whenever our subscriptions change, this map is cleared.
        Collection<Subscription> subsPerType = local.get(superType);

        if (subsPerType == null) {
            // this caches our class hierarchy. This is never cleared.
            Collection<Class<?>> types = getSuperClass(superType);
            if (types.isEmpty()) {
                local.put(superType, EMPTY_SUBS);
                return null;
            }

            Map<Class<?>, Collection<Subscription>> local2 = this.subscriptionsPerMessageSingle;
            subsPerType = new StrongConcurrentSetV8<Subscription>(types.size() + 1, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);

            Iterator<Class<?>> iterator = types.iterator();
            while (iterator.hasNext()) {
                Class<?> superClass = iterator.next();

                Collection<Subscription> subs = local2.get(superClass);
                if (subs != null && !subs.isEmpty()) {
                    for (Subscription sub : subs) {
                        if (sub.acceptsSubtypes()) {
                            subsPerType.add(sub);
                        }
                    }
                }
            }

            // cache our subscriptions for super classes, so that their access can be fast!
            // duplicates are OK.
            local.put(superType, subsPerType);
        }

        return subsPerType;
    }

    // must be protected by read lock
    // ALSO checks to see if the superClass accepts subtypes.
    public Collection<Subscription> getSuperSubscriptions(Class<?> superType1, Class<?> superType2) {
        this.superCheck.set(true);
        HashMapTree<Class<?>, Collection<Subscription>> local = this.superClassSubscriptionsMulti;

        // whenever our subscriptions change, this map is cleared.
        HashMapTree<Class<?>, Collection<Subscription>> subsPerTypeLeaf = local.getLeaf(superType1, superType2);
        Collection<Subscription> subsPerType = null;

        // we DO NOT care about duplicate, because the answers will be the same
        if (subsPerTypeLeaf != null) {
            // if the leaf exists, then the value exists.
            subsPerType = subsPerTypeLeaf.getValue();
        } else {
            subsPerType = new StrongConcurrentSetV8<Subscription>(16, LOAD_FACTOR, this.STRIPE_SIZE);

            // whenever our subscriptions change, this map is cleared.
            Collection<Class<?>> types1 = this.superClassesCache.get(superType1);
            Collection<Class<?>> types2 = this.superClassesCache.get(superType2);

            Collection<Subscription> subs;
            HashMapTree<Class<?>, Collection<Subscription>> leaf1;
            HashMapTree<Class<?>, Collection<Subscription>> leaf2;

            Iterator<Class<?>> iterator1 = new SuperClassIterator(superType1, types1);
            Iterator<Class<?>> iterator2;

            Class<?> eventSuperType1;
            Class<?> eventSuperType2;

            while (iterator1.hasNext()) {
                eventSuperType1 = iterator1.next();
                boolean type1Matches = eventSuperType1 == superType1;
                if (type1Matches) {
                    continue;
                }

                leaf1 = this.subscriptionsPerMessageMulti.getLeaf(eventSuperType1);
                if (leaf1 != null) {
                    iterator2 = new SuperClassIterator(superType2, types2);

                    while (iterator2.hasNext()) {
                        eventSuperType2 = iterator2.next();
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

            Collection<Subscription> putIfAbsent = local.putIfAbsent(subsPerType, superType1, superType2);
            if (putIfAbsent != null) {
                // someone beat us
                subsPerType = putIfAbsent;
            }
        }

        return subsPerType;
    }

    // must be protected by read lock
    // ALSO checks to see if the superClass accepts subtypes.
    public Collection<Subscription> getSuperSubscriptions(Class<?> superType1, Class<?> superType2, Class<?> superType3) {
        this.superCheck.set(true);
        HashMapTree<Class<?>, Collection<Subscription>> local = this.superClassSubscriptionsMulti;

        // whenever our subscriptions change, this map is cleared.
        HashMapTree<Class<?>, Collection<Subscription>> subsPerTypeLeaf = local.getLeaf(superType1, superType2, superType3);
        Collection<Subscription> subsPerType;


        // we DO NOT care about duplicate, because the answers will be the same
        if (subsPerTypeLeaf != null) {
            // if the leaf exists, then the value exists.
            subsPerType = subsPerTypeLeaf.getValue();
        } else {
            Collection<Class<?>> types1 = this.superClassesCache.get(superType1);
            Collection<Class<?>> types2 = this.superClassesCache.get(superType2);
            Collection<Class<?>> types3 = this.superClassesCache.get(superType3);

            subsPerType = new StrongConcurrentSetV8<Subscription>(16, LOAD_FACTOR, this.STRIPE_SIZE);

            Collection<Subscription> subs;
            HashMapTree<Class<?>, Collection<Subscription>> leaf1;
            HashMapTree<Class<?>, Collection<Subscription>> leaf2;
            HashMapTree<Class<?>, Collection<Subscription>> leaf3;

            Iterator<Class<?>> iterator1 = new SuperClassIterator(superType1, types1);
            Iterator<Class<?>> iterator2;
            Iterator<Class<?>> iterator3;

            Class<?> eventSuperType1;
            Class<?> eventSuperType2;
            Class<?> eventSuperType3;

            while (iterator1.hasNext()) {
                eventSuperType1 = iterator1.next();
                boolean type1Matches = eventSuperType1 == superType1;
                if (type1Matches) {
                    continue;
                }

                leaf1 = this.subscriptionsPerMessageMulti.getLeaf(eventSuperType1);
                if (leaf1 != null) {
                    iterator2 = new SuperClassIterator(superType2, types2);

                    while (iterator2.hasNext()) {
                        eventSuperType2 = iterator2.next();
                        boolean type12Matches = type1Matches && eventSuperType2 == superType2;
                        if (type12Matches) {
                            continue;
                        }

                        leaf2 = leaf1.getLeaf(eventSuperType2);

                        if (leaf2 != null) {
                            iterator3 = new SuperClassIterator(superType3, types3);

                            while (iterator3.hasNext()) {
                                eventSuperType3 = iterator3.next();
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

            Collection<Subscription> putIfAbsent = local.putIfAbsent(subsPerType, superType1, superType2, superType3);
            if (putIfAbsent != null) {
                // someone beat us
                subsPerType = putIfAbsent;
            }
        }

        return subsPerType;
    }
}
