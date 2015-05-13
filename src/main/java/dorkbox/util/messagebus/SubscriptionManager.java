package dorkbox.util.messagebus;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import dorkbox.util.messagebus.common.ConcurrentHashMapV8;
import dorkbox.util.messagebus.common.HashMapTree;
import dorkbox.util.messagebus.common.ISetEntry;
import dorkbox.util.messagebus.common.ReflectionUtils;
import dorkbox.util.messagebus.common.StrongConcurrentSetV8;
import dorkbox.util.messagebus.common.SubscriptionHolder;
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
    private static final StrongConcurrentSetV8<Subscription> EMPTY_SUBS = new StrongConcurrentSetV8<Subscription>(0, LOAD_FACTOR, 1);

    // the metadata reader that is used to inspect objects passed to the subscribe method
    private static final MetadataReader metadataReader = new MetadataReader();

    // remember already processed classes that do not contain any message handlers
    private final Map<Class<?>, Boolean> nonListeners;

    // all subscriptions per message type. We perpetually KEEP the types, as this lowers the amount of locking required
    // this is the primary list for dispatching a specific message
    // write access is synchronized and happens only when a listener of a specific class is registered the first time
    private final ConcurrentMap<Class<?>, StrongConcurrentSetV8<Subscription>> subscriptionsPerMessageSingle;
    private final HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>> subscriptionsPerMessageMulti;

    // all subscriptions per messageHandler type
    // this map provides fast access for subscribing and unsubscribing
    // write access is synchronized and happens very infrequently
    // once a collection of subscriptions is stored it does not change
    private final ConcurrentMap<Class<?>, StrongConcurrentSetV8<Subscription>> subscriptionsPerListener;

    private final Map<Class<?>, Class<?>> arrayVersionCache;
    private final Map<Class<?>, Boolean> isArrayCache;
    private final Map<Class<?>, StrongConcurrentSetV8<Class<?>>> superClassesCache;

    // superClassSubscriptions keeps track of all subscriptions of super classes. SUB/UNSUB dumps it, so it is recreated dynamically.
    // it's a hit on SUB/UNSUB, but REALLY improves performance on handlers
    // it's faster to create a new one for SUB/UNSUB than it is to clear() on the original one
    private final Map<Class<?>, StrongConcurrentSetV8<Subscription>> superClassSubscriptions;
    private final HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>> superClassSubscriptionsMulti;

    private final Map<Class<?>, StrongConcurrentSetV8<Subscription>> varArgSubscriptions;
    private final Map<Class<?>, StrongConcurrentSetV8<Subscription>> varArgSuperClassSubscriptions;
    private final HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>> varArgSuperClassSubscriptionsMulti;

    // stripe size of maps for concurrency
    private final int STRIPE_SIZE;

    private final SubscriptionHolder subHolderSingle;
    private final SubscriptionHolder subHolderConcurrent;


    SubscriptionManager(int numberOfThreads) {
        this.STRIPE_SIZE = numberOfThreads;

        // modified ONLY during SUB/UNSUB
        {
            this.nonListeners = new ConcurrentHashMapV8<Class<?>, Boolean>(4, SubscriptionManager.LOAD_FACTOR);

            this.subscriptionsPerMessageSingle = new ConcurrentHashMapV8<Class<?>, StrongConcurrentSetV8<Subscription>>(64, SubscriptionManager.LOAD_FACTOR, 1);
            this.subscriptionsPerMessageMulti = new HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>>(4, SubscriptionManager.LOAD_FACTOR);

            // only used during SUB/UNSUB
            this.subscriptionsPerListener = new ConcurrentHashMapV8<Class<?>, StrongConcurrentSetV8<Subscription>>(64, SubscriptionManager.LOAD_FACTOR, 1);
        }

        // modified by N threads
        {
            this.arrayVersionCache = new ConcurrentHashMapV8<Class<?>, Class<?>>(64, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);
            this.isArrayCache = new ConcurrentHashMapV8<Class<?>, Boolean>(64, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);
            this.superClassesCache = new ConcurrentHashMapV8<Class<?>, StrongConcurrentSetV8<Class<?>>>(64, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);

            // superClassSubscriptions keeps track of all subscriptions of super classes. SUB/UNSUB dumps it, so it is recreated dynamically.
            // it's a hit on SUB/UNSUB, but improves performance of handlers
            this.superClassSubscriptions = new ConcurrentHashMapV8<Class<?>, StrongConcurrentSetV8<Subscription>>(64, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);
            this.superClassSubscriptionsMulti = new HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>>(4, SubscriptionManager.LOAD_FACTOR);

            // var arg subscriptions keep track of which subscriptions can handle varArgs. SUB/UNSUB dumps it, so it is recreated dynamically.
            // it's a hit on SUB/UNSUB, but improves performance of handlers
            this.varArgSubscriptions = new ConcurrentHashMapV8<Class<?>, StrongConcurrentSetV8<Subscription>>(64, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);
            this.varArgSuperClassSubscriptions = new ConcurrentHashMapV8<Class<?>, StrongConcurrentSetV8<Subscription>>(64, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);
            this.varArgSuperClassSubscriptionsMulti = new HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>>(4, SubscriptionManager.LOAD_FACTOR);
        }

        this.subHolderSingle = new SubscriptionHolder(SubscriptionManager.LOAD_FACTOR, 1);
        this.subHolderConcurrent = new SubscriptionHolder(SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);
    }

    public void shutdown() {
        this.nonListeners.clear();

        this.subscriptionsPerMessageSingle.clear();
        this.subscriptionsPerMessageMulti.clear();
        this.subscriptionsPerListener.clear();

        this.superClassesCache.clear();
        this.arrayVersionCache.clear();
        this.isArrayCache.clear();

        clearConcurrentCollections();
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
            ConcurrentMap<Class<?>, StrongConcurrentSetV8<Subscription>> subsPerListener2 = this.subscriptionsPerListener;
            StrongConcurrentSetV8<Subscription> subsPerListener = subsPerListener2.get(listenerClass);
            if (subsPerListener == null) {
                // a listener is subscribed for the first time
                StrongConcurrentSetV8<MessageHandler> messageHandlers = SubscriptionManager.metadataReader.getMessageListener(listenerClass).getHandlers();
                int handlersSize = messageHandlers.size();

                if (handlersSize == 0) {
                    // remember the class as non listening class if no handlers are found
                    this.nonListeners.put(listenerClass, Boolean.TRUE);
                    return;
                } else {
                    subsPerListener = new StrongConcurrentSetV8<Subscription>(16, SubscriptionManager.LOAD_FACTOR, 1);
                    ConcurrentMap<Class<?>, StrongConcurrentSetV8<Subscription>> subsPerMessageSingle = this.subscriptionsPerMessageSingle;

                    ISetEntry<MessageHandler> current = messageHandlers.head;
                    MessageHandler messageHandler;
                    while (current != null) {
                        messageHandler = current.getValue();
                        current = current.next();

                        StrongConcurrentSetV8<Subscription> subsPerType = null;

                        // now add this subscription to each of the handled types
                        Class<?>[] types = messageHandler.getHandledMessages();
                        int size = types.length;

                        switch (size) {
                            case 1: {
                                SubscriptionHolder subHolderConcurrent = this.subHolderConcurrent;
                                StrongConcurrentSetV8<Subscription> putIfAbsent = subsPerMessageSingle.putIfAbsent(types[0], subHolderConcurrent.get());
                                if (putIfAbsent != null) {
                                    subsPerType = putIfAbsent;
                                } else {
                                    subsPerType = subHolderConcurrent.get();
                                    subHolderConcurrent.set(subHolderConcurrent.initialValue());
                                    getSuperClasses(types[0]);
                                }
                                break;
                            }
                            case 2: {
                                // the HashMapTree uses read/write locks, so it is only accessible one thread at a time
                                SubscriptionHolder subHolderSingle = this.subHolderSingle;
                                StrongConcurrentSetV8<Subscription> putIfAbsent = this.subscriptionsPerMessageMulti.putIfAbsent(subHolderSingle.get(), types[0], types[1]);
                                if (putIfAbsent != null) {
                                    subsPerType = putIfAbsent;
                                } else {
                                    subsPerType = subHolderSingle.get();
                                    subHolderSingle.set(subHolderSingle.initialValue());
                                    getSuperClasses(types[0]);
                                    getSuperClasses(types[1]);
                                }
                                break;
                            }
                            case 3: {
                                // the HashMapTree uses read/write locks, so it is only accessible one thread at a time
                                SubscriptionHolder subHolderSingle = this.subHolderSingle;
                                StrongConcurrentSetV8<Subscription> putIfAbsent = this.subscriptionsPerMessageMulti.putIfAbsent(subHolderSingle.get(), types[0], types[1], types[2]);
                                if (putIfAbsent != null) {
                                    subsPerType = putIfAbsent;
                                } else {
                                    subsPerType = subHolderSingle.get();
                                    subHolderSingle.set(subHolderSingle.initialValue());
                                    getSuperClasses(types[0]);
                                    getSuperClasses(types[1]);
                                    getSuperClasses(types[2]);
                                }
                                break;
                            }
                            default: {
                                // the HashMapTree uses read/write locks, so it is only accessible one thread at a time
                                SubscriptionHolder subHolderSingle = this.subHolderSingle;
                                StrongConcurrentSetV8<Subscription> putIfAbsent = this.subscriptionsPerMessageMulti.putIfAbsent(subHolderSingle.get(), types);
                                if (putIfAbsent != null) {
                                    subsPerType = putIfAbsent;
                                } else {
                                    subsPerType = subHolderSingle.get();
                                    subHolderSingle.set(subHolderSingle.initialValue());

                                    Class<?> c;
                                    int length = types.length;
                                    for (int i = 0; i < length; i++) {
                                        c = types[i];
                                        getSuperClasses(c);
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
                ISetEntry<Subscription> current = subsPerListener.head;
                Subscription subscription;
                while (current != null) {
                    subscription = current.getValue();
                    current = current.next();

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

        // these are concurrent collections
        clearConcurrentCollections();


        StrongConcurrentSetV8<Subscription> subscriptions = this.subscriptionsPerListener.get(listenerClass);
        if (subscriptions != null) {
            ISetEntry<Subscription> current = subscriptions.head;
            Subscription subscription;
            while (current != null) {
                subscription = current.getValue();
                current = current.next();

                subscription.unsubscribe(listener);
            }
        }
    }

    private void clearConcurrentCollections() {
        this.superClassSubscriptions.clear();
        this.varArgSubscriptions.clear();
        this.varArgSuperClassSubscriptions.clear();
        this.varArgSuperClassSubscriptionsMulti.clear();
    }

    /**
     * race conditions will result in duplicate answers, which we don't care if happens
     * never returns null
     * never reset, since it never needs to be reset (as the class hierarchy doesn't change at runtime)
     */
    private StrongConcurrentSetV8<Class<?>> getSuperClasses(Class<?> clazz) {
        // this is never reset, since it never needs to be.
        Map<Class<?>, StrongConcurrentSetV8<Class<?>>> local = this.superClassesCache;

        StrongConcurrentSetV8<Class<?>> superTypes = local.get(clazz);
        if (superTypes == null) {
            boolean isArray = clazz.isArray();

            // it doesn't matter if concurrent access stomps on values, since they are always the same.
            superTypes = ReflectionUtils.getSuperTypes(clazz);
            StrongConcurrentSetV8<Class<?>> set = new StrongConcurrentSetV8<Class<?>>(superTypes.size() + 1, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);

            ISetEntry<Class<?>> current = superTypes.head;
            Class<?> c;
            while (current != null) {
                c = current.getValue();
                current = current.next();

                if (isArray) {
                    c = getArrayClass(c);
                }
                set.add(c);
            }

            // race conditions will result in duplicate answers, which we don't care if happens
            local.put(clazz, set);
            return set;
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

    /**
     * Cache the values of JNI method, isArray(c)
     * @return true if the class c is an array type
     */
    @SuppressWarnings("boxing")
    public final boolean isArray(Class<?> c) {
        Boolean isArray = this.isArrayCache.get(c);
        if (isArray == null) {
            boolean b = c.isArray();
            this.isArrayCache.put(c, b);
            return b;
        }
        return isArray;
    }


    // CAN RETURN NULL
    public final StrongConcurrentSetV8<Subscription> getSubscriptionsByMessageType(Class<?> messageType) {
        return this.subscriptionsPerMessageSingle.get(messageType);
    }

    // CAN RETURN NULL
    public final StrongConcurrentSetV8<Subscription> getSubscriptionsByMessageType(Class<?> messageType1, Class<?> messageType2) {
        return this.subscriptionsPerMessageMulti.get(messageType1, messageType2);
    }


    // CAN RETURN NULL
    public final StrongConcurrentSetV8<Subscription> getSubscriptionsByMessageType(Class<?> messageType1, Class<?> messageType2, Class<?> messageType3) {
        return this.subscriptionsPerMessageMulti.getValue(messageType1, messageType2, messageType3);
    }

    // CAN RETURN NULL
    public final Collection<Subscription> getSubscriptionsByMessageType(Class<?>... messageTypes) {
        return this.subscriptionsPerMessageMulti.get(messageTypes);
    }

    // CAN RETURN NULL
    // check to see if the messageType can convert/publish to the "array" version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public StrongConcurrentSetV8<Subscription> getVarArgSubscriptions(Class<?> varArgType) {
        Map<Class<?>, StrongConcurrentSetV8<Subscription>> local = this.varArgSubscriptions;

        // whenever our subscriptions change, this map is cleared.
        StrongConcurrentSetV8<Subscription> subsPerType = local.get(varArgType);

        if (subsPerType == null) {
            // this caches our array type. This is never cleared.
            Class<?> arrayVersion = getArrayClass(varArgType);

            Map<Class<?>, StrongConcurrentSetV8<Subscription>> local2 = this.subscriptionsPerMessageSingle;
            subsPerType = new StrongConcurrentSetV8<Subscription>(local2.size(), SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);

            ISetEntry<Subscription> current;
            Subscription sub;

            StrongConcurrentSetV8<Subscription> subs = local2.get(arrayVersion);
            if (subs != null) {
                current = subs.head;
                while (current != null) {
                    sub = current.getValue();
                    current = current.next();

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

    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public StrongConcurrentSetV8<Subscription> getVarArgSuperSubscriptions(Class<?> varArgType) {
        Map<Class<?>, StrongConcurrentSetV8<Subscription>> local = this.varArgSuperClassSubscriptions;

        // whenever our subscriptions change, this map is cleared.
        StrongConcurrentSetV8<Subscription> subsPerType = local.get(varArgType);

        if (subsPerType == null) {
            // this caches our array type. This is never cleared.
            Class<?> arrayVersion = getArrayClass(varArgType);
            StrongConcurrentSetV8<Class<?>> types = getSuperClasses(arrayVersion);
            if (types.isEmpty()) {
                local.put(varArgType, EMPTY_SUBS);
                return EMPTY_SUBS;
            }

            Map<Class<?>, StrongConcurrentSetV8<Subscription>> local2 = this.subscriptionsPerMessageSingle;
            subsPerType = new StrongConcurrentSetV8<Subscription>(local2.size(), SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);

            ISetEntry<Subscription> current;
            Subscription sub;

            ISetEntry<Class<?>> current1;
            Class<?> superClass;

            current1 = types.head;
            while (current1 != null) {
                superClass = current1.getValue();
                current1 = current1.next();

                StrongConcurrentSetV8<Subscription> subs = local2.get(superClass);
                if (subs != null) {
                    current = subs.head;
                    while (current != null) {
                        sub = current.getValue();
                        current = current.next();

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

    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public StrongConcurrentSetV8<Subscription> getVarArgSuperSubscriptions(Class<?> messageClass1, Class<?> messageClass2) {
        HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>> local = this.varArgSuperClassSubscriptionsMulti;

        // whenever our subscriptions change, this map is cleared.
        HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>> subsPerTypeLeaf = local.getLeaf(messageClass1, messageClass2);
        StrongConcurrentSetV8<Subscription> subsPerType = null;

        // we DO NOT care about duplicate, because the answers will be the same
        if (subsPerTypeLeaf != null) {
            // if the leaf exists, then the value exists.
            subsPerType = subsPerTypeLeaf.getValue();
        } else {
            // the message class types are not the same, so look for a common superClass varArg subscription.
            // this is to publish to object[] (or any class[]) handler that is common among all superTypes of the messages
            StrongConcurrentSetV8<Subscription> varargSuperSubscriptions1 = getVarArgSuperSubscriptions(messageClass1);
            StrongConcurrentSetV8<Subscription> varargSuperSubscriptions2 = getVarArgSuperSubscriptions(messageClass2);

            subsPerType = new StrongConcurrentSetV8<Subscription>(varargSuperSubscriptions1.size(), SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);

            ISetEntry<Subscription> current;
            Subscription sub;

            current = varargSuperSubscriptions1.head;
            while (current != null) {
                sub = current.getValue();
                current = current.next();

                if (varargSuperSubscriptions2.contains(sub)) {
                    subsPerType.add(sub);
                }
            }

            StrongConcurrentSetV8<Subscription> putIfAbsent = local.putIfAbsent(subsPerType, messageClass1, messageClass2);
            if (putIfAbsent != null) {
                // someone beat us
                subsPerType = putIfAbsent;
            }
        }

        return subsPerType;
    }

    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public StrongConcurrentSetV8<Subscription> getVarArgSuperSubscriptions(final Class<?> messageClass1, final Class<?> messageClass2, final Class<?> messageClass3) {
        HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>> local = this.varArgSuperClassSubscriptionsMulti;

        // whenever our subscriptions change, this map is cleared.
        HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>> subsPerTypeLeaf = local.getLeaf(messageClass1, messageClass2, messageClass3);
        StrongConcurrentSetV8<Subscription> subsPerType = null;

        // we DO NOT care about duplicate, because the answers will be the same
        if (subsPerTypeLeaf != null) {
            // if the leaf exists, then the value exists.
            subsPerType = subsPerTypeLeaf.getValue();
        } else {
            // the message class types are not the same, so look for a common superClass varArg subscription.
            // this is to publish to object[] (or any class[]) handler that is common among all superTypes of the messages
            StrongConcurrentSetV8<Subscription> varargSuperSubscriptions1 = getVarArgSuperSubscriptions(messageClass1);
            StrongConcurrentSetV8<Subscription> varargSuperSubscriptions2 = getVarArgSuperSubscriptions(messageClass2);
            StrongConcurrentSetV8<Subscription> varargSuperSubscriptions3 = getVarArgSuperSubscriptions(messageClass3);

            subsPerType = new StrongConcurrentSetV8<Subscription>(varargSuperSubscriptions1.size(), SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);

            ISetEntry<Subscription> current;
            Subscription sub;

            current = varargSuperSubscriptions1.head;
            while (current != null) {
                sub = current.getValue();
                current = current.next();

                if (varargSuperSubscriptions2.contains(sub) && varargSuperSubscriptions3.contains(sub)) {
                    subsPerType.add(sub);
                }
            }

            StrongConcurrentSetV8<Subscription> putIfAbsent = local.putIfAbsent(subsPerType, messageClass1, messageClass2, messageClass3);
            if (putIfAbsent != null) {
                // someone beat us
                subsPerType = putIfAbsent;
            }
        }

        return subsPerType;
    }


    // CAN NOT RETURN NULL
    // ALSO checks to see if the superClass accepts subtypes.
    public final StrongConcurrentSetV8<Subscription> getSuperSubscriptions(Class<?> superType) {
        Map<Class<?>, StrongConcurrentSetV8<Subscription>> local = this.superClassSubscriptions;

        // whenever our subscriptions change, this map is cleared.
        StrongConcurrentSetV8<Subscription> subsPerType = local.get(superType);

        if (subsPerType == null) {
            // this caches our class hierarchy. This is never cleared.
            StrongConcurrentSetV8<Class<?>> types = getSuperClasses(superType);
            if (types.isEmpty()) {
                local.put(superType, EMPTY_SUBS);
                return EMPTY_SUBS;
            }

            Map<Class<?>, StrongConcurrentSetV8<Subscription>> local2 = this.subscriptionsPerMessageSingle;
            subsPerType = new StrongConcurrentSetV8<Subscription>(types.size() + 1, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);

            ISetEntry<Subscription> current;
            Subscription sub;

            ISetEntry<Class<?>> current1 = null;
            Class<?> superClass;

            current1 = types.head;
            while (current1 != null) {
                superClass = current1.getValue();
                current1 = current1.next();

                StrongConcurrentSetV8<Subscription> subs = local2.get(superClass);
                if (subs != null) {
                    current = subs.head;
                    while (current != null) {
                        sub = current.getValue();
                        current = current.next();

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

    // CAN NOT RETURN NULL
    // ALSO checks to see if the superClass accepts subtypes.
    public StrongConcurrentSetV8<Subscription> getSuperSubscriptions(Class<?> superType1, Class<?> superType2) {
        HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>> local = this.superClassSubscriptionsMulti;

        // whenever our subscriptions change, this map is cleared.
        HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>> subsPerTypeLeaf = local.getLeaf(superType1, superType2);
        StrongConcurrentSetV8<Subscription> subsPerType = null;

        // we DO NOT care about duplicate, because the answers will be the same
        if (subsPerTypeLeaf != null) {
            // if the leaf exists, then the value exists.
            subsPerType = subsPerTypeLeaf.getValue();
        } else {
            subsPerType = new StrongConcurrentSetV8<Subscription>(16, LOAD_FACTOR, this.STRIPE_SIZE);

            // whenever our subscriptions change, this map is cleared.
            StrongConcurrentSetV8<Class<?>> types1 = this.superClassesCache.get(superType1);
            StrongConcurrentSetV8<Class<?>> types2 = this.superClassesCache.get(superType2);

            StrongConcurrentSetV8<Subscription> subs;
            HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>> leaf1;
            HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>> leaf2;

            ISetEntry<Subscription> current = null;
            Subscription sub;

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
                                current = subs.head;
                                while (current != null) {
                                    sub = current.getValue();
                                    current = current.next();

                                    if (sub.acceptsSubtypes()) {
                                        subsPerType.add(sub);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            StrongConcurrentSetV8<Subscription> putIfAbsent = local.putIfAbsent(subsPerType, superType1, superType2);
            if (putIfAbsent != null) {
                // someone beat us
                subsPerType = putIfAbsent;
            }
        }

        return subsPerType;
    }

    // CAN NOT RETURN NULL
    // ALSO checks to see if the superClass accepts subtypes.
    public StrongConcurrentSetV8<Subscription> getSuperSubscriptions(Class<?> superType1, Class<?> superType2, Class<?> superType3) {
        HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>> local = this.superClassSubscriptionsMulti;

        // whenever our subscriptions change, this map is cleared.
        HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>> subsPerTypeLeaf = local.getLeaf(superType1, superType2, superType3);
        StrongConcurrentSetV8<Subscription> subsPerType;


        // we DO NOT care about duplicate, because the answers will be the same
        if (subsPerTypeLeaf != null) {
            // if the leaf exists, then the value exists.
            subsPerType = subsPerTypeLeaf.getValue();
        } else {
            StrongConcurrentSetV8<Class<?>> types1 = this.superClassesCache.get(superType1);
            StrongConcurrentSetV8<Class<?>> types2 = this.superClassesCache.get(superType2);
            StrongConcurrentSetV8<Class<?>> types3 = this.superClassesCache.get(superType3);

            subsPerType = new StrongConcurrentSetV8<Subscription>(16, LOAD_FACTOR, this.STRIPE_SIZE);

            StrongConcurrentSetV8<Subscription> subs;
            HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>> leaf1;
            HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>> leaf2;
            HashMapTree<Class<?>, StrongConcurrentSetV8<Subscription>> leaf3;

            ISetEntry<Subscription> current = null;
            Subscription sub;

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
                                        current = subs.head;
                                        while (current != null) {
                                            sub = current.getValue();
                                            current = current.next();

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

            StrongConcurrentSetV8<Subscription> putIfAbsent = local.putIfAbsent(subsPerType, superType1, superType2, superType3);
            if (putIfAbsent != null) {
                // someone beat us
                subsPerType = putIfAbsent;
            }
        }

        return subsPerType;
    }
}
