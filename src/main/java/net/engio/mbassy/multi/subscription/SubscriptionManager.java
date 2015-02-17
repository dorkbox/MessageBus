package net.engio.mbassy.multi.subscription;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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


    // the metadata reader that is used to inspect objects passed to the subscribe method
    private final MetadataReader metadataReader = new MetadataReader();

    // all subscriptions per message type
    // this is the primary list for dispatching a specific message
    // write access is synchronized and happens only when a listener of a specific class is registered the first time
    private final Map<Class<?>, Collection<Subscription>> subscriptionsPerMessageSingle = new IdentityHashMap<Class<?>, Collection<Subscription>>(50);
    private final IdentityObjectTree<Class<?>, Collection<Subscription>> subscriptionsPerMessageMulti = new IdentityObjectTree<Class<?>, Collection<Subscription>>();

    // all subscriptions per messageHandler type
    // this map provides fast access for subscribing and unsubscribing
    // write access is synchronized and happens very infrequently
    // once a collection of subscriptions is stored it does not change
    private final Map<Class<?>, Collection<Subscription>> subscriptionsPerListener = new IdentityHashMap<Class<?>, Collection<Subscription>>(50);

    private final Object holder = new Object[0];

    // remember classes that can have VarArg casting performed
    private final ConcurrentHashMap<Class<?>, Class<?>> varArgClasses = new ConcurrentHashMap<Class<?>, Class<?>>();

    private final Map<Class<?>, ArrayDeque<Class<?>>> superClassesCache = new IdentityHashMap<Class<?>, ArrayDeque<Class<?>>>();
//    private final Map<Class<?>, Collection<Subscription>> superClassSubscriptionsPerMessageSingle = new IdentityHashMap<Class<?>, Collection<Subscription>>(50);



    // remember already processed classes that do not contain any message handlers
    private final ConcurrentHashMap<Class<?>, Object> nonListeners = new ConcurrentHashMap<Class<?>, Object>();

    // synchronize read/write acces to the subscription maps
    private final ReentrantReadWriteUpdateLock LOCK = new ReentrantReadWriteUpdateLock();

    public SubscriptionManager() {
    }

    public void unsubscribe(Object listener) {
        if (listener == null) {
            return;
        }

        Class<?> listenerClass = listener.getClass();
        Collection<Subscription> subscriptions;
        boolean nothingLeft = true;
        try {
            this.LOCK.updateLock().lock();

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
                                }
                            }
//                            Collection<Subscription> superSubs = this.superClassSubscriptionsPerMessageSingle.get(clazz);
//                            if (superSubs != null) {
//                                superSubs.remove(subscription);
//
//                                if (superSubs.isEmpty()) {
//                                    // remove element
//                                    this.superClassSubscriptionsPerMessageSingle.remove(clazz);
//                                }
//                            }
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
                                        this.LOCK.writeLock().lock();
                                        // remove tree element
                                        switch (size) {
                                            case 2: this.subscriptionsPerMessageMulti.remove(handledMessageTypes[0], handledMessageTypes[1]); break;
                                            case 3: this.subscriptionsPerMessageMulti.remove(handledMessageTypes[1], handledMessageTypes[1], handledMessageTypes[2]); break;
                                            default: this.subscriptionsPerMessageMulti.remove(handledMessageTypes); break;
                                        }
                                        this.LOCK.writeLock().unlock();
                                    }
                                }
                            }
                        }
                    }

                    nothingLeft &= isEmpty;
                }
            }

            if (nothingLeft) {
                this.LOCK.writeLock().lock();
                this.subscriptionsPerListener.remove(listenerClass);
                this.LOCK.writeLock().unlock();
            }

        } finally {
            this.LOCK.updateLock().unlock();
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
        try {
            this.LOCK.updateLock().lock();
            subscriptions = this.subscriptionsPerListener.get(listenerClass);

            if (subscriptions != null) {
                // subscriptions already exist and must only be updated
                for (Subscription subscription : subscriptions) {
                    subscription.subscribe(listener);
                }
            } else {
                // a listener is subscribed for the first time
                try {
                    this.LOCK.writeLock().lock(); // upgrade updatelock to write lock, Avoid DCL

                    // a listener is subscribed for the first time
                    List<MessageHandler> messageHandlers = this.metadataReader.getMessageListener(listenerClass).getHandlers();
                    if (messageHandlers.isEmpty()) {
                        // remember the class as non listening class if no handlers are found
                        this.nonListeners.put(listenerClass, this.holder);
                        return;
                    }

                    // it's SAFE to use non-concurrent collection here (read only). Same thread LOCKS on this with a write lock
                    subscriptions = new StrongConcurrentSet<Subscription>(messageHandlers.size());

                    // create subscriptions for all detected message handlers
                    for (MessageHandler messageHandler : messageHandlers) {
                        // create the subscription
                        Subscription subscription = new Subscription(messageHandler);
                        subscription.subscribe(listener);

                        // single or multi?
                        Class<?>[] handledMessageTypes = subscription.getHandledMessageTypes();
                        int size = handledMessageTypes.length;
                        if (size == 1) {
                            // single
                            Class<?> clazz = handledMessageTypes[0];

                            Collection<Subscription> subs = this.subscriptionsPerMessageSingle.get(clazz);
//                            Collection<Subscription> superSubs = this.superClassSubscriptionsPerMessageSingle.get(clazz);
                            if (subs == null) {
                                // NOTE: Order is important for safe publication
                                subs = new StrongConcurrentSet<Subscription>(2);
                                subs.add(subscription);
                                this.subscriptionsPerMessageSingle.put(clazz, subs);

//                                if (subscription.acceptsSubtypes()) {
//                                    superSubs = new StrongConcurrentSet<Subscription>(2);
//                                    superSubs.add(subscription);
//                                    this.superClassSubscriptionsPerMessageSingle.put(clazz, superSubs);
//                                }
                            } else {
                                subs.add(subscription);

//                                if (subscription.acceptsSubtypes()) {
//                                    superSubs.add(subscription);
//                                }
                            }

                            // have to save our the VarArg class types, because creating var-arg arrays for objects is expensive
                            if (subscription.isVarArg()) {
                                Class<?> componentType = clazz.getComponentType();
                                this.varArgClasses.putIfAbsent(componentType, clazz);

                                // since it's vararg, this means that it's an ARRAY, so we ALSO
                                // have to add the component classes of the array
                                if (subscription.acceptsSubtypes()) {
                                    ArrayDeque<Class<?>> superClasses = setupSuperClassCache(componentType);

                                    // have to setup each vararg chain
                                    for (Class<?> superClass : superClasses) {
                                        if (!this.varArgClasses.containsKey(superClass)) {
                                            // this is expensive, so we check the cache first
                                            Class<?> c2 = Array.newInstance(superClass, 1).getClass();
                                            this.varArgClasses.put(superClass, c2);
                                        }
                                    }
                                }
                            } else if (subscription.acceptsSubtypes()) {
                                setupSuperClassCache(clazz);
                            }
                        }
                        else {
                            // NOTE: Not thread-safe! must be synchronized in outer scope
                            IdentityObjectTree<Class<?>, Collection<Subscription>> tree;

                            switch (size) {
                                case 2: {
                                    tree = this.subscriptionsPerMessageMulti.createLeaf(handledMessageTypes[0], handledMessageTypes[1]);
                                    if (subscription.acceptsSubtypes()) {
                                        setupSuperClassCache(handledMessageTypes[0]);
                                        setupSuperClassCache(handledMessageTypes[1]);
                                    }
                                    break;
                                }
                                case 3: {
                                    tree = this.subscriptionsPerMessageMulti.createLeaf(handledMessageTypes[0], handledMessageTypes[1], handledMessageTypes[2]);
                                    if (subscription.acceptsSubtypes()) {
                                        setupSuperClassCache(handledMessageTypes[0]);
                                        setupSuperClassCache(handledMessageTypes[1]);
                                        setupSuperClassCache(handledMessageTypes[2]);
                                    }
                                    break;
                                }
                                default: {
                                    tree = this.subscriptionsPerMessageMulti.createLeaf(handledMessageTypes);
                                    if (subscription.acceptsSubtypes()) {
                                        for (Class<?> c : handledMessageTypes) {
                                            setupSuperClassCache(c);
                                        }
                                    }
                                    break;
                                }
                            }

                            Collection<Subscription> subs = tree.getValue();
                            if (subs == null) {
                                subs = new LinkedList<Subscription>();
                                tree.putValue(subs);
                            }
                            subs.add(subscription);
                        }

                        subscriptions.add(subscription);
                    }

                    this.subscriptionsPerListener.put(listenerClass, subscriptions);
                } finally {
                    this.LOCK.writeLock().unlock();
                }
            }
        } finally {
            this.LOCK.updateLock().unlock();
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
        Collection<Class<?>> types = this.superClassesCache.get(superType);
        if (types == null || types.isEmpty()) {
            return null;
        }

        Collection<Subscription> subsPerType = new ArrayDeque<Subscription>(DEFAULT_SUPER_CLASS_TREE_SIZE);

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

        return subsPerType;
    }

    // must be protected by read lock
    // ALSO checks to see if the superClass accepts subtypes.
    public Collection<Subscription> getSuperSubscriptions(Class<?> superType1, Class<?> superType2) {
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

    // not a thread safe collection, but it doesn't matter
    private ArrayDeque<Class<?>> setupSuperClassCache(Class<?> clazz) {
        ArrayDeque<Class<?>> types = this.superClassesCache.get(clazz);

        if (types == null) {
            // it doesn't matter if concurrent access stomps on values, since they are always the same.
            Set<Class<?>> superTypes = ReflectionUtils.getSuperTypes(clazz);
            types = new ArrayDeque<Class<?>>(superTypes);
            // NOTE: no need to write lock, since race conditions will result in duplicate answers (which we don't care about)
            this.superClassesCache.put(clazz, types);
        }

        return types;
    }



    ///////////////
    // a var-arg handler might match
    ///////////////
    private void addVarArgClass(Collection<Subscription> subscriptions, Class<?> messageType) {
        // tricky part. We have to check the ARRAY version
        Collection<Subscription> subs;

        Class<?> varArgClass = this.varArgClasses.get(messageType);
        if (varArgClass != null) {
            // also add all subscriptions that match super types
            subs = this.subscriptionsPerMessageSingle.get(varArgClass);
            if (subs != null) {
                for (Subscription sub : subs) {
                    subscriptions.add(sub);
                }
            }
        }
    }

    // must be protected by read lock
    public Collection<Subscription> getVarArgs(Class<?> clazz) {
        Class<?> varArgClass = this.varArgClasses.get(clazz);
        if (varArgClass != null) {
            return this.subscriptionsPerMessageSingle.get(varArgClass);
        }
        return null;
    }

    // must be protected by read lock
    public Collection<Subscription> getVarArgs(Class<?> clazz1, Class<?> clazz2) {
        if (clazz1 == clazz2) {
            Class<?> varArgClass = this.varArgClasses.get(clazz1);
            if (varArgClass != null) {
                return this.subscriptionsPerMessageSingle.get(varArgClass);
            }
        }
        return null;
    }

    // must be protected by read lock
    public Collection<Subscription> getVarArgs(Class<?> clazz1, Class<?> clazz2, Class<?> clazz3) {
        if (clazz1 == clazz2 && clazz2 == clazz3) {
            Class<?> varArgClass = this.varArgClasses.get(clazz1);
            if (varArgClass != null) {
                return this.subscriptionsPerMessageSingle.get(varArgClass);
            }
        }
        return null;
    }

    // must be protected by read lock
    public Collection<Subscription> getVarArgs(Class<?>... classes) {
        // classes IS ALREADY ALL SAME TYPE!
        Class<?> firstClass = classes[0];

        Class<?> varArgClass = this.varArgClasses.get(firstClass);
        if (varArgClass != null) {
            return this.subscriptionsPerMessageSingle.get(varArgClass);
        }
        return null;
    }







    ///////////////
    // a var-arg handler might match
    // tricky part. We have to check the ARRAY version
    ///////////////
    private void addVarArgClasses(Collection<Subscription> subscriptions, Class<?> messageType, ArrayDeque<Class<?>> types1) {
        Collection<Subscription> subs;

        Class<?> varArgClass = this.varArgClasses.get(messageType);
        if (varArgClass != null) {
            // also add all subscriptions that match super types
            subs = this.subscriptionsPerMessageSingle.get(varArgClass);
            if (subs != null) {
                for (Subscription sub : subs) {
                    subscriptions.add(sub);
                }
            }
        }

        for (Class<?> eventSuperType : types1) {
            varArgClass = this.varArgClasses.get(eventSuperType);
            if (varArgClass != null) {
                // also add all subscriptions that match super types
                subs = this.subscriptionsPerMessageSingle.get(varArgClass);
                if (subs != null) {
                    for (Subscription sub : subs) {
                        subscriptions.add(sub);
                    }
                }
            }
        }
    }

    private void getSubsVarArg(Collection<Subscription> subscriptions, int length, int index,
                               IdentityObjectTree<Class<?>, Collection<Subscription>> tree, Class<?>[] messageTypes) {

        Class<?> classType = messageTypes[index];
        // get all the super types, if there are any.
        ArrayDeque<Class<?>> superClasses = setupSuperClassCache(classType);

        IdentityObjectTree<Class<?>, Collection<Subscription>> leaf;
        Collection<Subscription> subs;

        Class<?> superClass = classType;
        int i;
        int newIndex;

//        for (i = -1; i < superClasses.size(); i++) {
//            if (i > -1) {
//                superClass = superClasses.get(i);
//            }
//            leaf = tree.getLeaf(superClass);
//            if (leaf != null) {
//                newIndex = index+1;
//                if (index == length) {
//                    subs = leaf.getValue();
//                    if (subs != null) {
//                        for (Subscription sub : subs) {
//                            if (sub.handlesMessageType(messageTypes)) {
//                                subscriptions.add(sub);
//                            }
//                        }
//                    }
//                } else {
//                    getSubsVarArg(subscriptions, length, newIndex, leaf, messageTypes);
//                }
//            }
//        }
    }

    public void readLock() {
        this.LOCK.readLock().lock();
    }

    public void readUnLock() {
        this.LOCK.readLock().unlock();
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
