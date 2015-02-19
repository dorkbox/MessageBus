package net.engio.mbassy.multi.subscription;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;

import net.engio.mbassy.multi.common.IdentityObjectTree;
import net.engio.mbassy.multi.common.ReflectionUtils;
import net.engio.mbassy.multi.common.StrongConcurrentSet;
import net.engio.mbassy.multi.listener.MessageHandler;
import net.engio.mbassy.multi.listener.MetadataReader;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import com.googlecode.concurentlocks.ReentrantReadWriteUpdateLock;

/**
 * The subscription managers responsibility is to consistently handle and synchronize the message listener subscription process.
 * It provides fast lookup of existing subscriptions when another instance of an already known
 * listener is subscribed and takes care of creating new set of subscriptions for any unknown class that defines
 * message handlers.
 *
 * @author bennidi
 *         Date: 5/11/13
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class SubscriptionManager {
    private static final int DEFAULT_SUPER_CLASS_TREE_SIZE = 4;

    private final int MAP_STRIPING;
    private float LOAD_FACTOR;

    // the metadata reader that is used to inspect objects passed to the subscribe method
    private final MetadataReader metadataReader = new MetadataReader();

    // all subscriptions per message type
    // this is the primary list for dispatching a specific message
    // write access is synchronized and happens only when a listener of a specific class is registered the first time
    private final Map<Class<?>, Collection<Subscription>> subscriptionsPerMessageSingle;
    private final IdentityObjectTree<Class<?>, Collection<Subscription>> subscriptionsPerMessageMulti;

    // all subscriptions per messageHandler type
    // this map provides fast access for subscribing and unsubscribing
    // write access is synchronized and happens very infrequently
    // once a collection of subscriptions is stored it does not change
    private final Map<Class<?>, Collection<Subscription>> subscriptionsPerListener;

    private final Object holder = new Object[0];

    private final Map<Class<?>, Collection<Class<?>>> superClassesCache;

    // superClassSubscriptions keeps track of all subscriptions of super classes. SUB/UNSUB dumps it, so it is recreated dynamically.
    // it's a hit on SUB/UNSUB, but REALLY improves performance on handlers
    // it's faster to create a new one for SUB/UNSUB than it is to clear() on the original one
    private volatile Map<Class<?>, Collection<Subscription>> superClassSubscriptions;
//    private final IdentityObjectTree<Class<?>, Collection<Subscription>> superClassSubscriptionsMulti = new IdentityObjectTree<Class<?>, Collection<Subscription>>();


    // remember already processed classes that do not contain any message handlers
    private final Map<Class<?>, Object> nonListeners;

    // synchronize read/write acces to the subscription maps
    private final ReentrantReadWriteUpdateLock LOCK = new ReentrantReadWriteUpdateLock();

    public SubscriptionManager(int numberOfThreads) {
        this.MAP_STRIPING = 1;
        this.LOAD_FACTOR = 0.8f;

//        this.subscriptionsPerMessageSingle = new IdentityHashMap<Class<?>, Collection<Subscription>>(4);
//        this.subscriptionsPerMessageMulti = new IdentityObjectTree<Class<?>, Collection<Subscription>>();
//
//        // only used during SUB/UNSUB
//        this.subscriptionsPerListener = new IdentityHashMap<Class<?>, Collection<Subscription>>(4);
//
//        this.superClassesCache = new IdentityHashMap<Class<?>, Collection<Class<?>>>(8);

        this.subscriptionsPerMessageSingle = new ConcurrentHashMap<Class<?>, Collection<Subscription>>(4, this.LOAD_FACTOR, this.MAP_STRIPING);
        this.subscriptionsPerMessageMulti = new IdentityObjectTree<Class<?>, Collection<Subscription>>();

        // only used during SUB/UNSUB
        this.subscriptionsPerListener = new ConcurrentHashMap<Class<?>, Collection<Subscription>>(4, this.LOAD_FACTOR, 1);

        this.superClassesCache = new ConcurrentHashMap<Class<?>, Collection<Class<?>>>(8, this.LOAD_FACTOR, this.MAP_STRIPING);

        this.nonListeners = new ConcurrentHashMap<Class<?>, Object>(4, this.LOAD_FACTOR, this.MAP_STRIPING);
    }

    private final void resetSuperClassSubs() {
        // superClassSubscriptions keeps track of all subscriptions of super classes. SUB/UNSUB dumps it, so it is recreated dynamically.
        // it's a hit on SUB/UNSUB, but improves performance on handlers
        this.superClassSubscriptions = new ConcurrentHashMap<Class<?>, Collection<Subscription>>(8, this.LOAD_FACTOR, this.MAP_STRIPING);
    }

    public void unsubscribe(Object listener) {
        if (listener == null) {
            return;
        }

        Class<?> listenerClass = listener.getClass();
        Collection<Subscription> subscriptions;
        boolean nothingLeft = true;
        Lock UPDATE = this.LOCK.updateLock();
        try {
            UPDATE.lock();

            subscriptions = this.subscriptionsPerListener.get(listenerClass);

            if (subscriptions != null) {
                for (Subscription subscription : subscriptions) {
                    subscription.unsubscribe(listener);

                    boolean isEmpty = subscription.isEmpty();

                    if (isEmpty) {
                        // single or multi?
                        Class<?>[] handledMessageTypes = subscription.getHandledMessageTypes();
                        int size = handledMessageTypes.length;
                        if (size == 1) {
                            // single
                            Class<?> clazz = handledMessageTypes[0];

                            // NOTE: Order is important for safe publication
                            Collection<Subscription> subs = this.subscriptionsPerMessageSingle.get(clazz);
                            if (subs != null) {
                                subs.remove(subscription);

                                if (subs.isEmpty()) {
                                    // remove element
                                    this.subscriptionsPerMessageSingle.remove(clazz);

                                    resetSuperClassSubs();
                                }
                            }
                        } else {
                            // NOTE: Not thread-safe! must be synchronized in outer scope
                            IdentityObjectTree<Class<?>, Collection<Subscription>> tree;

                            switch (size) {
                                case 2: tree = this.subscriptionsPerMessageMulti.getLeaf(handledMessageTypes[0], handledMessageTypes[1]); break;
                                case 3: tree = this.subscriptionsPerMessageMulti.getLeaf(handledMessageTypes[1], handledMessageTypes[1], handledMessageTypes[2]); break;
                                default: tree = this.subscriptionsPerMessageMulti.getLeaf(handledMessageTypes); break;
                            }

                            if (tree != null) {
                                Collection<Subscription> subs = tree.getValue();
                                if (subs != null) {
                                    subs.remove(subscription);

                                    if (subs.isEmpty()) {
                                        // remove tree element
                                        switch (size) {
                                            case 2: this.subscriptionsPerMessageMulti.remove(handledMessageTypes[0], handledMessageTypes[1]); break;
                                            case 3: this.subscriptionsPerMessageMulti.remove(handledMessageTypes[1], handledMessageTypes[1], handledMessageTypes[2]); break;
                                            default: this.subscriptionsPerMessageMulti.remove(handledMessageTypes); break;
                                        }
                                    }
                                }
                            }
                        }
                    }

                    nothingLeft &= isEmpty;
                }
            }

            if (nothingLeft) {
                Lock WRITE = this.LOCK.writeLock();
                WRITE.lock();
                this.subscriptionsPerListener.remove(listenerClass);
                WRITE.unlock();
            }

        } finally {
            UPDATE.unlock();
        }

        return;
    }


    // when a class is subscribed, the registrations for that class are permanent in the "subscriptionsPerListener"?
    public void subscribe(Object listener) {
        Class<?> listenerClass = listener.getClass();

        if (this.nonListeners.containsKey(listenerClass)) {
            // early reject of known classes that do not define message handlers
            return;
        }

        Collection<Subscription> subscriptions;
        Lock UPDATE = this.LOCK.updateLock();
        try {
            UPDATE.lock();
            subscriptions = this.subscriptionsPerListener.get(listenerClass);

            if (subscriptions != null) {
                // subscriptions already exist and must only be updated
                for (Subscription subscription : subscriptions) {
                    subscription.subscribe(listener);
                }
            } else {
                Lock WRITE = this.LOCK.writeLock();
                try {
                    WRITE.lock(); // upgrade updatelock to write lock, Avoid DCL

                    // a listener is subscribed for the first time
                    Collection<MessageHandler> messageHandlers = this.metadataReader.getMessageListener(listenerClass).getHandlers();
                    if (messageHandlers.isEmpty()) {
                        // remember the class as non listening class if no handlers are found
                        this.nonListeners.put(listenerClass, this.holder);
                        return;
                    }

                    subscriptions = new StrongConcurrentSet<Subscription>(8, this.LOAD_FACTOR);

                    resetSuperClassSubs();

                    // create NEW subscriptions for all detected message handlers
                    for (MessageHandler messageHandler : messageHandlers) {
                        // create the subscription
                        Subscription subscription = new Subscription(messageHandler);
                        subscription.subscribe(listener);

                        // single or multi?
                        Class<?>[] handledMessageTypes = subscription.getHandledMessageTypes();
                        int size = handledMessageTypes.length;
                        boolean acceptsSubtypes = subscription.acceptsSubtypes();

                        if (size == 1) {
                            // single
                            Class<?> clazz = handledMessageTypes[0];

                            Collection<Subscription> subs = this.subscriptionsPerMessageSingle.get(clazz);
                            if (subs == null) {
                                // NOTE: Order is important for safe publication
                                subs = new StrongConcurrentSet<Subscription>(8, this.LOAD_FACTOR);
                                subs.add(subscription);
                                this.subscriptionsPerMessageSingle.put(clazz, subs);

                            } else {
                                subs.add(subscription);
                            }

                            if (acceptsSubtypes) {
                                setupSuperClassCache(clazz);
                            }
                        }
                        else {
                            // NOTE: Not thread-safe! must be synchronized in outer scope
                            IdentityObjectTree<Class<?>, Collection<Subscription>> tree;

                            switch (size) {
                                case 2: {
                                    tree = this.subscriptionsPerMessageMulti.createLeaf(handledMessageTypes[0], handledMessageTypes[1]);
                                    if (acceptsSubtypes) {
                                        setupSuperClassCache(handledMessageTypes[0]);
                                        setupSuperClassCache(handledMessageTypes[1]);
                                    }
                                    break;
                                }
                                case 3: {
                                    tree = this.subscriptionsPerMessageMulti.createLeaf(handledMessageTypes[0], handledMessageTypes[1], handledMessageTypes[2]);
                                    if (acceptsSubtypes) {
                                        setupSuperClassCache(handledMessageTypes[0]);
                                        setupSuperClassCache(handledMessageTypes[1]);
                                        setupSuperClassCache(handledMessageTypes[2]);
                                    }
                                    break;
                                }
                                default: {
                                    tree = this.subscriptionsPerMessageMulti.createLeaf(handledMessageTypes);
                                    if (acceptsSubtypes) {
                                        for (Class<?> c : handledMessageTypes) {
                                            setupSuperClassCache(c);
                                        }
                                    }
                                    break;
                                }
                            }

                            Collection<Subscription> subs = tree.getValue();
                            if (subs == null) {
                                subs = new StrongConcurrentSet<Subscription>(16, this.LOAD_FACTOR);
                                tree.putValue(subs);
                            }
                            subs.add(subscription);
                        }

                        subscriptions.add(subscription);
                    }

                    this.subscriptionsPerListener.put(listenerClass, subscriptions);
                } finally {
                    WRITE.unlock();
                }
            }
        } finally {
            UPDATE.unlock();
        }
    }

    // must be protected by read lock
    // CAN RETURN NULL - not thread safe.
    public Collection<Subscription> getSubscriptionsByMessageType(Class<?> messageType) {
        return this.subscriptionsPerMessageSingle.get(messageType);
    }

    // must be protected by read lock
    // CAN RETURN NULL - not thread safe.
    public Collection<Subscription> getSubscriptionsByMessageType(Class<?> messageType1, Class<?> messageType2) {
        return this.subscriptionsPerMessageMulti.getValue(messageType1, messageType2);
    }


    // must be protected by read lock
    // CAN RETURN NULL - not thread safe.
    public Collection<Subscription> getSubscriptionsByMessageType(Class<?> messageType1, Class<?> messageType2, Class<?> messageType3) {
        return this.subscriptionsPerMessageMulti.getValue(messageType1, messageType2, messageType3);
    }

    // must be protected by read lock
    // CAN RETURN NULL - not thread safe.
    public Collection<Subscription> getSubscriptionsByMessageType(Class<?>... messageTypes) {
        return this.subscriptionsPerMessageMulti.getValue(messageTypes);
    }


    // must be protected by read lock
    // ALSO checks to see if the superClass accepts subtypes.
    public Collection<Subscription> getSuperSubscriptions(Class<?> superType) {
        Map<Class<?>, Collection<Subscription>> superClassSubs = this.superClassSubscriptions;
        if (superClassSubs == null) {
            // we haven't created it yet (via subscribe)
            return null;
        }

        Collection<Subscription> subsPerType = superClassSubs.get(superType);

        if (subsPerType == null) {
            Collection<Class<?>> types = this.superClassesCache.get(superType);
            if (types == null || types.isEmpty()) {
                return null;
            }

            subsPerType = new StrongConcurrentSet<Subscription>(16, this.LOAD_FACTOR);

            for (Class<?> superClass : types) {
                Collection<Subscription> subs = this.subscriptionsPerMessageSingle.get(superClass);
                if (subs != null) {
                    for (Subscription sub : subs) {
                        if (sub.acceptsSubtypes()) {
                            subsPerType.add(sub);
                        }
                    }
                }
            }

            superClassSubs.put(superType, subsPerType);
        }

        return subsPerType;
    }

    // must be protected by read lock
    // ALSO checks to see if the superClass accepts subtypes.
    public Collection<Subscription> getSuperSubscriptions(Class<?> superType1, Class<?> superType2) {
//        Collection<Subscription> subsPerType2 = this.superClassSubscriptions.get();


        // not thread safe. DO NOT MODIFY
        Collection<Class<?>> types1 = this.superClassesCache.get(superType1);
        Collection<Class<?>> types2 = this.superClassesCache.get(superType2);

        Collection<Subscription> subsPerType = new ArrayDeque<Subscription>(DEFAULT_SUPER_CLASS_TREE_SIZE);

        Collection<Subscription> subs;
        IdentityObjectTree<Class<?>, Collection<Subscription>> leaf1;
        IdentityObjectTree<Class<?>, Collection<Subscription>> leaf2;

        Iterator<Class<?>> iterator1 = new SuperClassIterator(superType1, types1);
        Iterator<Class<?>> iterator2;

        Class<?> eventSuperType1;
        Class<?> eventSuperType2;

        while (iterator1.hasNext()) {
            eventSuperType1 = iterator1.next();
            boolean type1Matches = eventSuperType1 == superType1;

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

        return subsPerType;
    }

    // must be protected by read lock
    // ALSO checks to see if the superClass accepts subtypes.
    public Collection<Subscription> getSuperSubscriptions(Class<?> superType1, Class<?> superType2, Class<?> superType3) {
        // not thread safe. DO NOT MODIFY
        Collection<Class<?>> types1 = this.superClassesCache.get(superType1);
        Collection<Class<?>> types2 = this.superClassesCache.get(superType2);
        Collection<Class<?>> types3 = this.superClassesCache.get(superType3);

        Collection<Subscription> subsPerType = new ArrayDeque<Subscription>(DEFAULT_SUPER_CLASS_TREE_SIZE);

        Collection<Subscription> subs;
        IdentityObjectTree<Class<?>, Collection<Subscription>> leaf1;
        IdentityObjectTree<Class<?>, Collection<Subscription>> leaf2;
        IdentityObjectTree<Class<?>, Collection<Subscription>> leaf3;

        Iterator<Class<?>> iterator1 = new SuperClassIterator(superType1, types1);
        Iterator<Class<?>> iterator2;
        Iterator<Class<?>> iterator3;

        Class<?> eventSuperType1;
        Class<?> eventSuperType2;
        Class<?> eventSuperType3;

        while (iterator1.hasNext()) {
            eventSuperType1 = iterator1.next();
            boolean type1Matches = eventSuperType1 == superType1;

            leaf1 = this.subscriptionsPerMessageMulti.getLeaf(eventSuperType1);
            if (leaf1 != null) {
                iterator2 = new SuperClassIterator(superType2, types2);

                while (iterator2.hasNext()) {
                    eventSuperType2 = iterator2.next();
                    boolean type12Matches = type1Matches && eventSuperType2 == superType2;

                    leaf2 = leaf1.getLeaf(eventSuperType2);

                    if (leaf2 != null) {
                        iterator3 = new SuperClassIterator(superType3, types3);

                        while (iterator3.hasNext()) {
                            eventSuperType3 = iterator3.next();
                            if (type12Matches && eventSuperType3 == superType3) {
                                continue;
                            }

                            leaf3 = leaf2.getLeaf(eventSuperType3);

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

        return subsPerType;
    }

    private Collection<Class<?>> setupSuperClassCache(Class<?> clazz) {
        Collection<Class<?>> types = this.superClassesCache.get(clazz);

        if (types == null) {
            // it doesn't matter if concurrent access stomps on values, since they are always the same.
            Set<Class<?>> superTypes = ReflectionUtils.getSuperTypes(clazz);
            types = new ArrayDeque<Class<?>>(superTypes);

            // race conditions will result in duplicate answers, which we don't care about
            this.superClassesCache.put(clazz, types);
        }

        return types;
    }

    public static class SuperClassIterator implements Iterator<Class<?>> {
        private final Iterator<Class<?>> iterator;
        private Class<?> clazz;

        public SuperClassIterator(Class<?> clazz, Collection<Class<?>> types) {
            this.clazz = clazz;
            if (types != null) {
                this.iterator = types.iterator();
            } else {
                this.iterator = null;
            }
        }

        @Override
        public boolean hasNext() {
            if (this.clazz != null) {
                return true;
            }

            if (this.iterator != null) {
                return this.iterator.hasNext();
            }

            return false;
        }

        @Override
        public Class<?> next() {
            if (this.clazz != null) {
                Class<?> clazz2 = this.clazz;
                this.clazz = null;

                return clazz2;
            }

            if (this.iterator != null) {
                return this.iterator.next();
            }

            return null;
        }

        @Override
        public void remove() {
            throw new NotImplementedException();
        }
    }
}
