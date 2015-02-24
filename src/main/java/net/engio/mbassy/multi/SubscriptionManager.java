package net.engio.mbassy.multi;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import net.engio.mbassy.multi.common.ConcurrentHashMapV8;
import net.engio.mbassy.multi.common.IdentityObjectTree;
import net.engio.mbassy.multi.common.ReflectionUtils;
import net.engio.mbassy.multi.common.StrongConcurrentSet;
import net.engio.mbassy.multi.listener.MessageHandler;
import net.engio.mbassy.multi.listener.MetadataReader;
import net.engio.mbassy.multi.subscription.Subscription;

/**
 * The subscription managers responsibility is to consistently handle and synchronize the message listener subscription process.
 * It provides fast lookup of existing subscriptions when another instance of an already known
 * listener is subscribed and takes care of creating new set of subscriptions for any unknown class that defines
 * message handlers.
 *
 *
 * Subscribe/Unsubscribe, while it is possible for them to be 100% concurrent (in relation to listeners per subscription),
 * getting an accurate reflection of the number of subscriptions, or guaranteeing a "HAPPENS-BEFORE" relationship really
 * complicates this.
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
    private final int STRIPE_SIZE;
    private float LOAD_FACTOR;

    // the metadata reader that is used to inspect objects passed to the subscribe method
    private static final MetadataReader metadataReader = new MetadataReader();

    // remember already processed classes that do not contain any message handlers
    private final Map<Class<?>, Boolean> nonListeners;

    // all subscriptions per message type. We perpetually KEEP the types, as this lowers the amount of locking required
    // this is the primary list for dispatching a specific message
    // write access is synchronized and happens only when a listener of a specific class is registered the first time
    private final Map<Class<?>, Collection<Subscription>> subscriptionsPerMessageSingle;
    private final IdentityObjectTree<Class<?>, Collection<Subscription>> subscriptionsPerMessageMulti;
    // synchronize read/write access to the subscription maps
    private final ReentrantLock lock = new ReentrantLock();


    // all subscriptions per messageHandler type
    // this map provides fast access for subscribing and unsubscribing
    // write access is synchronized and happens very infrequently
    // once a collection of subscriptions is stored it does not change
    private final Map<Class<?>, Collection<Subscription>> subscriptionsPerListener;

    private final Map<Class<?>, Set<Class<?>>> superClassesCache;

    // superClassSubscriptions keeps track of all subscriptions of super classes. SUB/UNSUB dumps it, so it is recreated dynamically.
    // it's a hit on SUB/UNSUB, but REALLY improves performance on handlers
    // it's faster to create a new one for SUB/UNSUB than it is to clear() on the original one
    private Map<Class<?>, Set<Subscription>> superClassSubscriptions;
//    private final IdentityObjectTree<Class<?>, Collection<Subscription>> superClassSubscriptionsMulti = new IdentityObjectTree<Class<?>, Collection<Subscription>>();


    SubscriptionManager(int numberOfThreads) {
        this.STRIPE_SIZE = numberOfThreads;
        this.LOAD_FACTOR = 0.8f;

        this.nonListeners = new ConcurrentHashMapV8<Class<?>, Boolean>(4, 0.8F, this.STRIPE_SIZE);

        this.subscriptionsPerMessageSingle = new ConcurrentHashMapV8<Class<?>, Collection<Subscription>>(4, this.LOAD_FACTOR);
        this.subscriptionsPerMessageMulti = new IdentityObjectTree<Class<?>, Collection<Subscription>>();

        // only used during SUB/UNSUB
        this.subscriptionsPerListener = new ConcurrentHashMapV8<Class<?>, Collection<Subscription>>(4, this.LOAD_FACTOR);

        this.superClassesCache = new ConcurrentHashMapV8<Class<?>, Set<Class<?>>>(8, this.LOAD_FACTOR, this.STRIPE_SIZE);
        // superClassSubscriptions keeps track of all subscriptions of super classes. SUB/UNSUB dumps it, so it is recreated dynamically.
        // it's a hit on SUB/UNSUB, but improves performance on handlers
        this.superClassSubscriptions = new ConcurrentHashMapV8<Class<?>, Set<Subscription>>(8, this.LOAD_FACTOR, this.STRIPE_SIZE);
    }

    private final void resetSuperClassSubs() {
        this.superClassSubscriptions.clear();
    }

    /**
     * Can ONLY be called by a single thread, in order to guarantee a "happens-before" relationship to subscriptions
     */
    public void subscribe(Object listener) {
        if (listener == null) {
            return;
        }

        Class<?> listenerClass = listener.getClass();

        if (this.nonListeners.containsKey(listenerClass)) {
            // early reject of known classes that do not define message handlers
            return;
        }

        this.lock.lock();
        try {
            Collection<Subscription> subscriptions = this.subscriptionsPerListener.get(listenerClass);
            if (subscriptions == null) {
                // a listener is subscribed for the first time
                resetSuperClassSubs();

                Collection<MessageHandler> messageHandlers = SubscriptionManager.metadataReader.getMessageListener(listenerClass).getHandlers();
                int handlersSize = messageHandlers.size();

                if (handlersSize == 0) {
                    // remember the class as non listening class if no handlers are found
                    this.nonListeners.put(listener.getClass(), Boolean.TRUE);
                    return;
                } else {
                    subscriptions = new StrongConcurrentSet<Subscription>(messageHandlers.size(), this.LOAD_FACTOR);

                    // create NEW subscriptions for all detected message handlers
                    for (MessageHandler messageHandler : messageHandlers) {
                        // create the subscription
                        Subscription subscription = new Subscription(messageHandler);
                        subscription.subscribe(listener);

                        subscriptions.add(subscription);

                        // now add this subscription to each of the handled types
                        Class<?>[] handledMessageTypes = subscription.getHandledMessageTypes();
                        int size = handledMessageTypes.length;
                        if (size == 1) {
                            // single
                            Class<?> clazz = handledMessageTypes[0];

                            Collection<Subscription> subs = this.subscriptionsPerMessageSingle.get(clazz);
                            if (subs == null || subs.isEmpty()) {
                                subs = new StrongConcurrentSet<Subscription>(8, this.LOAD_FACTOR);
                                this.subscriptionsPerMessageSingle.put(clazz, subs);
                            }

                            subs.add(subscription);
                            setupSuperClassCache(clazz);
                        } else {
                            // multiversion
                        }
                    }

                    // order is critical for safe publication
                    this.subscriptionsPerListener.put(listenerClass, subscriptions);
                }
            } else {
                // subscriptions already exist and must only be updated
                for (Subscription subscription : subscriptions) {
                    subscription.subscribe(listener);
                }
            }

        } finally {
            this.lock.unlock();
        }





//        } else {
//            // subscriptions already exist and must only be updated
//            for (Subscription subscription : subscriptions) {
//                subscription.subscribe(listener);
//            }
//        }

//
//        if (subscriptions != null) {
//            // subscriptions already exist and must only be updated
//            for (Subscription subscription : subscriptions) {
//                subscription.subscribe(listener);
//            }
//        }
//            else {
//                // a listener is subscribed for the first time
//                Collection<MessageHandler> messageHandlers = this.metadataReader.getMessageListener(listenerClass).getHandlers();
//                int handlersSize = messageHandlers.size();
//
//                if (handlersSize == 0) {
//                    // remember the class as non listening class if no handlers are found
//                    this.nonListeners.put(listenerClass, this.holder);
//                } else {
//                    subscriptions = new StrongConcurrentSet<Subscription>(handlersSize, this.LOAD_FACTOR);
////                        subscriptions = Collections.newSetFromMap(new ConcurrentHashMap<Subscription, Boolean>(8, this.LOAD_FACTOR, this.MAP_STRIPING));
////                        subscriptions = Collections.newSetFromMap(new ConcurrentHashMap<Subscription, Boolean>(8, this.LOAD_FACTOR, 1));
////                        subscriptions = Collections.newSetFromMap(new Reference2BooleanOpenHashMap<Subscription>(8, this.LOAD_FACTOR));
//                    this.subscriptionsPerListener.put(listenerClass, subscriptions);
//
//                    resetSuperClassSubs();
//
//                    // create NEW subscriptions for all detected message handlers
//                    for (MessageHandler messageHandler : messageHandlers) {
//                        // create the subscription
//                        Subscription subscription = new Subscription(messageHandler);
//                        subscription.subscribe(listener);
//
//                        subscriptions.add(subscription);
//
//                        //
//                        // save the subscription per message type
//                        //
//                        // single or multi?
//                        Class<?>[] handledMessageTypes = subscription.getHandledMessageTypes();
//                        int size = handledMessageTypes.length;
//                        boolean acceptsSubtypes = subscription.acceptsSubtypes();
//
//                        if (size == 1) {
//                            // single
//                            Class<?> clazz = handledMessageTypes[0];
//
//                            Collection<Subscription> subs = this.subscriptionsPerMessageSingle.get(clazz);
//                            if (subs == null) {
//                                Collection<Subscription> putIfAbsent = this.subscriptionsPerMessageSingle.putIfAbsent(clazz, this.subInitialValue.get());
//                                if (putIfAbsent != null) {
//                                    subs = putIfAbsent;
//                                } else {
//                                    subs = this.subInitialValue.get();
////                                        this.subInitialValue.set(Collections.newSetFromMap(new ConcurrentHashMap<Subscription, Boolean>(8, this.LOAD_FACTOR, 1)));
////                                        this.subInitialValue.set(Collections.newSetFromMap(new Reference2BooleanOpenHashMap<Subscription>(8, this.LOAD_FACTOR)));
//                                    this.subInitialValue.set(new StrongConcurrentSet<Subscription>(8, this.LOAD_FACTOR));
////                                        this.subInitialValue.set(new ArrayDeque<Subscription>(8));
//                                }
//                            }
//
//                            subs.add(subscription);
//
//                            if (acceptsSubtypes) {
//                                // race conditions will result in duplicate answers, which we don't care about
//                                setupSuperClassCache(clazz);
//                            }
//                        }
//                        else {
////                                // NOTE: Not thread-safe! must be synchronized in outer scope
////                                IdentityObjectTree<Class<?>, Collection<Subscription>> tree;
////
////                                switch (size) {
////                                    case 2: {
////                                        tree = this.subscriptionsPerMessageMulti.createLeaf(handledMessageTypes[0], handledMessageTypes[1]);
////                                        if (acceptsSubtypes) {
////                                            setupSuperClassCache(handledMessageTypes[0]);
////                                            setupSuperClassCache(handledMessageTypes[1]);
////                                        }
////                                        break;
////                                    }
////                                    case 3: {
////                                        tree = this.subscriptionsPerMessageMulti.createLeaf(handledMessageTypes[0], handledMessageTypes[1], handledMessageTypes[2]);
////                                        if (acceptsSubtypes) {
////                                            setupSuperClassCache(handledMessageTypes[0]);
////                                            setupSuperClassCache(handledMessageTypes[1]);
////                                            setupSuperClassCache(handledMessageTypes[2]);
////                                        }
////                                        break;
////                                    }
////                                    default: {
////                                        tree = this.subscriptionsPerMessageMulti.createLeaf(handledMessageTypes);
////                                        if (acceptsSubtypes) {
////                                            for (Class<?> c : handledMessageTypes) {
////                                                setupSuperClassCache(c);
////                                            }
////                                        }
////                                        break;
////                                    }
////                                }
////
////                                Collection<Subscription> subs = tree.getValue();
////                                if (subs == null) {
////                                    subs = new StrongConcurrentSet<Subscription>(16, this.LOAD_FACTOR);
////                                    tree.putValue(subs);
////                                }
////                                subs.add(subscription);
//                        }
//                    }
//                }
//            }
    }

    /**
     * Can ONLY be called by a single thread, in order to guarantee a "happens-before" relationship to subscriptions
     */
    public final void unsubscribe(Object listener) {
        if (listener == null) {
            return;
        }

        Class<?> listenerClass = listener.getClass();
        if (this.nonListeners.containsKey(listenerClass)) {
            // early reject of known classes that do not define message handlers
            return;
        }

        resetSuperClassSubs();

        this.lock.lock();
        try {
            // these are a concurrent collection
            Collection<Subscription> subscriptions = this.subscriptionsPerListener.get(listenerClass);
            if (subscriptions != null) {

                for (Subscription subscription : subscriptions) {
                    subscription.unsubscribe(listener); // this is thread safe, but the following stuff is NOT thread safe.

                    boolean isEmpty = subscription.isEmpty();

                    if (isEmpty) {
                        // single or multi?
                        Class<?>[] handledMessageTypes = subscription.getHandledMessageTypes();
                        int size = handledMessageTypes.length;
                        if (size == 1) {
                            // single
                            Class<?> clazz = handledMessageTypes[0];

                            Collection<Subscription> subs = this.subscriptionsPerMessageSingle.get(clazz);
                            if (subs != null) {
                                subs.remove(subscription);

                                if (subs.isEmpty()) {
                                    // remove element
                                    this.subscriptionsPerMessageSingle.remove(clazz);
                                }
                            }
                        } else {
//                            // NOTE: Not thread-safe! must be synchronized in outer scope
//                            IdentityObjectTree<Class<?>, Collection<Subscription>> tree;
    //
//                            switch (size) {
//                                case 2: tree = this.subscriptionsPerMessageMulti.getLeaf(handledMessageTypes[0], handledMessageTypes[1]); break;
//                                case 3: tree = this.subscriptionsPerMessageMulti.getLeaf(handledMessageTypes[1], handledMessageTypes[1], handledMessageTypes[2]); break;
//                                default: tree = this.subscriptionsPerMessageMulti.getLeaf(handledMessageTypes); break;
//                            }
    //
//                            if (tree != null) {
//                                Collection<Subscription> subs = tree.getValue();
//                                if (subs != null) {
//                                    subs.remove(subscription);
    //
//                                    if (subs.isEmpty()) {
//                                        // remove tree element
//                                        switch (size) {
//                                            case 2: this.subscriptionsPerMessageMulti.remove(handledMessageTypes[0], handledMessageTypes[1]); break;
//                                            case 3: this.subscriptionsPerMessageMulti.remove(handledMessageTypes[1], handledMessageTypes[1], handledMessageTypes[2]); break;
//                                            default: this.subscriptionsPerMessageMulti.remove(handledMessageTypes); break;
//                                        }
//                                    }
//                                }
//                            }
                        }
                    }
                }
            }
        } finally {
            this.lock.unlock();
        }
    }


    // CAN RETURN NULL
    public final Collection<Subscription> getSubscriptionsByMessageType(Class<?> messageType) {
        return this.subscriptionsPerMessageSingle.get(messageType);
    }

    // CAN RETURN NULL
    public final Collection<Subscription> getSubscriptionsByMessageType(Class<?> messageType1, Class<?> messageType2) {
        return this.subscriptionsPerMessageMulti.getValue(messageType1, messageType2);
    }


    // CAN RETURN NULL
    public final Collection<Subscription> getSubscriptionsByMessageType(Class<?> messageType1, Class<?> messageType2, Class<?> messageType3) {
        return this.subscriptionsPerMessageMulti.getValue(messageType1, messageType2, messageType3);
    }

    // CAN RETURN NULL
    public final Collection<Subscription> getSubscriptionsByMessageType(Class<?>... messageTypes) {
        return this.subscriptionsPerMessageMulti.getValue(messageTypes);
    }



    // ALSO checks to see if the superClass accepts subtypes.
    public final Collection<Subscription> getSuperSubscriptions(Class<?> superType) {
        // whenever our subscriptions change, this map is cleared.
        Set<Subscription> subsPerType = this.superClassSubscriptions.get(superType);

        if (subsPerType == null) {
            // this caches our class hierarchy. This is never cleared.
            Collection<Class<?>> types = setupSuperClassCache(superType);
            if (types.isEmpty()) {
                return null;
            }
            subsPerType = new StrongConcurrentSet<Subscription>(types.size() + 1, this.LOAD_FACTOR);

            Iterator<Class<?>> iterator = types.iterator();
            while (iterator.hasNext()) {
                Class<?> superClass = iterator.next();

                Collection<Subscription> subs = this.subscriptionsPerMessageSingle.get(superClass);
                if (subs != null && !subs.isEmpty()) {
                    for (Subscription sub : subs) {
                        if (sub.acceptsSubtypes()) {
                            subsPerType.add(sub);
                        }
                    }
                }
            }

            // cache our subscriptions for super classes, so that their access can be fast!
            this.superClassSubscriptions.put(superType, subsPerType);
        }

        return subsPerType;
    }

    // must be protected by read lock
    // ALSO checks to see if the superClass accepts subtypes.
    public void getSuperSubscriptions(Class<?> superType1, Class<?> superType2) {
//        Collection<Subscription> subsPerType2 = this.superClassSubscriptions.get();
//
//
//        // not thread safe. DO NOT MODIFY
//        Collection<Class<?>> types1 = this.superClassesCache.get(superType1);
//        Collection<Class<?>> types2 = this.superClassesCache.get(superType2);
//
//        Collection<Subscription> subsPerType = new ArrayDeque<Subscription>(DEFAULT_SUPER_CLASS_TREE_SIZE);
//
//        Collection<Subscription> subs;
//        IdentityObjectTree<Class<?>, Collection<Subscription>> leaf1;
//        IdentityObjectTree<Class<?>, Collection<Subscription>> leaf2;
//
//        Iterator<Class<?>> iterator1 = new SuperClassIterator(superType1, types1);
//        Iterator<Class<?>> iterator2;
//
//        Class<?> eventSuperType1;
//        Class<?> eventSuperType2;
//
//        while (iterator1.hasNext()) {
//            eventSuperType1 = iterator1.next();
//            boolean type1Matches = eventSuperType1 == superType1;
//
//            leaf1 = this.subscriptionsPerMessageMulti.getLeaf(eventSuperType1);
//            if (leaf1 != null) {
//                iterator2 = new SuperClassIterator(superType2, types2);
//
//                while (iterator2.hasNext()) {
//                    eventSuperType2 = iterator2.next();
//                    if (type1Matches && eventSuperType2 == superType2) {
//                        continue;
//                    }
//
//                    leaf2 = leaf1.getLeaf(eventSuperType2);
//
//                    if (leaf2 != null) {
//                        subs = leaf2.getValue();
//                        if (subs != null) {
//                            for (Subscription sub : subs) {
//                                if (sub.acceptsSubtypes()) {
//                                    subsPerType.add(sub);
//                                }
//                            }
//                        }
//                    }
//                }
//            }
//        }

//        return subsPerType;
    }

    // must be protected by read lock
    // ALSO checks to see if the superClass accepts subtypes.
    public void getSuperSubscriptions(Class<?> superType1, Class<?> superType2, Class<?> superType3) {
//        // not thread safe. DO NOT MODIFY
//        Collection<Class<?>> types1 = this.superClassesCache.get(superType1);
//        Collection<Class<?>> types2 = this.superClassesCache.get(superType2);
//        Collection<Class<?>> types3 = this.superClassesCache.get(superType3);
//
//        Collection<Subscription> subsPerType = new ArrayDeque<Subscription>(DEFAULT_SUPER_CLASS_TREE_SIZE);
//
//        Collection<Subscription> subs;
//        IdentityObjectTree<Class<?>, Collection<Subscription>> leaf1;
//        IdentityObjectTree<Class<?>, Collection<Subscription>> leaf2;
//        IdentityObjectTree<Class<?>, Collection<Subscription>> leaf3;
//
//        Iterator<Class<?>> iterator1 = new SuperClassIterator(superType1, types1);
//        Iterator<Class<?>> iterator2;
//        Iterator<Class<?>> iterator3;
//
//        Class<?> eventSuperType1;
//        Class<?> eventSuperType2;
//        Class<?> eventSuperType3;
//
//        while (iterator1.hasNext()) {
//            eventSuperType1 = iterator1.next();
//            boolean type1Matches = eventSuperType1 == superType1;
//
//            leaf1 = this.subscriptionsPerMessageMulti.getLeaf(eventSuperType1);
//            if (leaf1 != null) {
//                iterator2 = new SuperClassIterator(superType2, types2);
//
//                while (iterator2.hasNext()) {
//                    eventSuperType2 = iterator2.next();
//                    boolean type12Matches = type1Matches && eventSuperType2 == superType2;
//
//                    leaf2 = leaf1.getLeaf(eventSuperType2);
//
//                    if (leaf2 != null) {
//                        iterator3 = new SuperClassIterator(superType3, types3);
//
//                        while (iterator3.hasNext()) {
//                            eventSuperType3 = iterator3.next();
//                            if (type12Matches && eventSuperType3 == superType3) {
//                                continue;
//                            }
//
//                            leaf3 = leaf2.getLeaf(eventSuperType3);
//
//                            subs = leaf3.getValue();
//                            if (subs != null) {
//                                for (Subscription sub : subs) {
//                                    if (sub.acceptsSubtypes()) {
//                                        subsPerType.add(sub);
//                                    }
//                                }
//                            }
//                        }
//                    }
//                }
//            }
//        }
//
//        return subsPerType;
    }


    /**
     * race conditions will result in duplicate answers, which we don't care if happens
     * never returns null
     */
    private Collection<Class<?>> setupSuperClassCache(Class<?> clazz) {
        Collection<Class<?>> superTypes = this.superClassesCache.get(clazz);
        if (superTypes == null) {
            // it doesn't matter if concurrent access stomps on values, since they are always the same.
            superTypes = ReflectionUtils.getSuperTypes(clazz);
            StrongConcurrentSet<Class<?>> set = new StrongConcurrentSet<Class<?>>(superTypes.size() + 1, this.LOAD_FACTOR);
            for (Class<?> c : superTypes) {
                set.add(c);
            }

            // race conditions will result in duplicate answers, which we don't care about
            this.superClassesCache.put(clazz, set);
        }

        return superTypes;
    }
}
