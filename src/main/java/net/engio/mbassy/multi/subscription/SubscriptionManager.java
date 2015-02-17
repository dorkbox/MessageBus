package net.engio.mbassy.multi.subscription;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
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

    private final Map<Class<?>, ArrayList<Class<?>>> superClassesCache = new IdentityHashMap<Class<?>, ArrayList<Class<?>>>();

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

                            // NOTE: Order is important for safe publication
                            Collection<Subscription> subs = this.subscriptionsPerMessageSingle.get(clazz);
                            if (subs == null) {
                                subs = new StrongConcurrentSet<Subscription>(2);
                                subs.add(subscription);
                                this.subscriptionsPerMessageSingle.put(clazz, subs);
                            } else {
                                subs.add(subscription);
                            }

                            // have to save our the VarArg class types, because creating var-arg arrays for objects is expensive
                            if (subscription.isVarArg()) {
                                Class<?> componentType = clazz.getComponentType();
                                this.varArgClasses.putIfAbsent(componentType, clazz);

                                // since it's vararg, this means that it's an ARRAY, so we ALSO
                                // have to add the component classes of the array
                                if (subscription.acceptsSubtypes()) {
                                    ArrayList<Class<?>> setupSuperClassCache2 = setupSuperClassCache(componentType);
                                    // have to setup each vararg chain
                                    for (int i = 0; i < setupSuperClassCache2.size(); i++) {
                                        Class<?> superClass = setupSuperClassCache2.get(i);

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

    private final Collection<Subscription> EMPTY_LIST = Collections.emptyList();


    // cannot return null, not thread safe.
    public Collection<Subscription> getSubscriptionsByMessageType(Class<?> messageType) {
        Collection<Subscription> subs = this.subscriptionsPerMessageSingle.get(messageType);
        if (subs != null) {

            return subs;
        } else {
            return this.EMPTY_LIST;
        }
    }


    // obtain the set of subscriptions for the given message type
    // Note: never returns null!
    public Collection<Subscription> DEPRECATED_getSubscriptionsByMessageType(Class<?> messageType) {
        // thread safe publication
        Collection<Subscription> subscriptions;

        try {
            this.LOCK.readLock().lock();

            int count = 0;
            Collection<Subscription> subs = this.subscriptionsPerMessageSingle.get(messageType);
            if (subs != null) {
                subscriptions = new ArrayDeque<Subscription>(count);
                subscriptions.addAll(subs);
            } else {
                subscriptions = new ArrayDeque<Subscription>(16);
            }

            // also add all subscriptions that match super types
            ArrayList<Class<?>> types1 = setupSuperClassCache(messageType);
            if (types1 != null) {
                Class<?> eventSuperType;
                int i;
                for (i = 0; i < types1.size(); i++) {
                    eventSuperType = types1.get(i);
                    subs = this.subscriptionsPerMessageSingle.get(eventSuperType);
                    if (subs != null) {
                        for (Subscription sub : subs) {
                            if (sub.handlesMessageType(messageType)) {
                                subscriptions.add(sub);
                            }
                        }
                    }
                    addVarArgClass(subscriptions, eventSuperType);
                }
            }

            addVarArgClass(subscriptions, messageType);

        } finally {
            this.LOCK.readLock().unlock();
        }

        return subscriptions;
    }


    // obtain the set of subscriptions for the given message types
    // Note: never returns null!
    public Collection<Subscription> getSubscriptionsByMessageType(Class<?> messageType1, Class<?> messageType2) {
        // thread safe publication
        Collection<Subscription> subscriptions = new ArrayDeque<Subscription>();

        try {
            this.LOCK.readLock().lock();

            // also add all subscriptions that match super types
            ArrayList<Class<?>> types1 = setupSuperClassCache(messageType1);
            ArrayList<Class<?>> types2 = setupSuperClassCache(messageType2);

            Collection<Subscription> subs;
            Class<?> eventSuperType1 = messageType1;
            IdentityObjectTree<Class<?>, Collection<Subscription>> leaf1;
            Class<?> eventSuperType2 = messageType1;
            IdentityObjectTree<Class<?>, Collection<Subscription>> leaf2;

            int i;
            int j;
            for (i = -1; i < types1.size(); i++) {
                if (i > -1) {
                    eventSuperType1 = types1.get(i);
                }
                leaf1 = this.subscriptionsPerMessageMulti.getLeaf(eventSuperType1);

                if (leaf1 != null) {
                    for (j = -1; j < types2.size(); j++) {
                        if (j > -1) {
                            eventSuperType2 = types2.get(j);
                        }
                        leaf2 = leaf1.getLeaf(eventSuperType2);

                        if (leaf2 != null) {
                            subs = leaf2.getValue();
                            if (subs != null) {
                                for (Subscription sub : subs) {
                                    if (sub.handlesMessageType(messageType1, messageType2)) {
                                        subscriptions.add(sub);
                                    }
                                }
                            }
                        }
                    }
                }
            }


            ///////////////
            // if they are ALL the same type, a var-arg handler might match
            ///////////////
            if (messageType1 == messageType2) {
                addVarArgClasses(subscriptions, messageType1, types1);
            }
        } finally {
            this.LOCK.readLock().unlock();
        }

        return subscriptions;
    }


    // obtain the set of subscriptions for the given message types
    // Note: never returns null!
    public Collection<Subscription> getSubscriptionsByMessageType(Class<?> messageType1, Class<?> messageType2, Class<?> messageType3) {
        // thread safe publication
        Collection<Subscription> subscriptions = new ArrayDeque<Subscription>();

        try {
            this.LOCK.readLock().lock();

            // also add all subscriptions that match super types
            ArrayList<Class<?>> types1 = setupSuperClassCache(messageType1);
            ArrayList<Class<?>> types2 = setupSuperClassCache(messageType2);
            ArrayList<Class<?>> types3 = setupSuperClassCache(messageType3);

            Class<?> eventSuperType1 = messageType1;
            IdentityObjectTree<Class<?>, Collection<Subscription>> leaf1;
            Class<?> eventSuperType2 = messageType2;
            IdentityObjectTree<Class<?>, Collection<Subscription>> leaf2;
            Class<?> eventSuperType3 = messageType3;
            IdentityObjectTree<Class<?>, Collection<Subscription>> leaf3;

            Collection<Subscription> subs;
            int i;
            int j;
            int k;
            for (i = -1; i < types1.size(); i++) {
                if (i > -1) {
                    eventSuperType1 = types1.get(i);
                }
                leaf1 = this.subscriptionsPerMessageMulti.getLeaf(eventSuperType1);

                if (leaf1 != null) {
                    for (j = -1; j < types2.size(); j++) {
                        if (j > -1) {
                            eventSuperType2 = types2.get(j);
                        }
                        leaf2 = leaf1.getLeaf(eventSuperType2);

                        if (leaf2 != null) {
                            for (k = -1; k < types3.size(); k++) {
                                if (k > -1) {
                                    eventSuperType3 = types3.get(k);
                                }
                                leaf3 = leaf2.getLeaf(eventSuperType3);

                                if (leaf3 != null) {
                                    subs = leaf3.getValue();
                                    if (subs != null) {
                                        for (Subscription sub : subs) {
                                            if (sub.handlesMessageType(messageType1, messageType2, messageType3)) {
                                                subscriptions.add(sub);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }


            ///////////////
            // if they are ALL the same type, a var-arg handler might match
            ///////////////
            if (messageType1 == messageType2 && messageType2 == messageType3) {
                addVarArgClasses(subscriptions, messageType1, types1);
            }
        } finally {
            this.LOCK.readLock().unlock();
        }

        return subscriptions;
    }

    // obtain the set of subscriptions for the given message types
    // Note: never returns null!
    public Collection<Subscription> getSubscriptionsByMessageType(Class<?>... messageTypes) {
        // thread safe publication
        Collection<Subscription> subscriptions = new ArrayDeque<Subscription>();

        try {
            this.LOCK.readLock().lock();

            int count = 16;

            // NOTE: Not thread-safe! must be synchronized in outer scope
            Collection<Subscription> subs = this.subscriptionsPerMessageMulti.getValue(messageTypes);
            if (subs != null) {
                for (Subscription sub : subs) {
                    if (sub.handlesMessageType(messageTypes)) {
                        subscriptions.add(sub);
                    }
                }
            }

            int size = messageTypes.length;
            if (size > 0) {
                boolean allSameType = true;
                Class<?> firstType = messageTypes[0];

                int i;
                for (i = 0;i<size;i++) {
                    if (messageTypes[i] != firstType) {
                        allSameType = false;
                    }
                }


                // add all subscriptions that match super types combinations
                // have to use recursion for this. BLEH
                getSubsVarArg(subscriptions, size-1, 0, this.subscriptionsPerMessageMulti, messageTypes);

                ///////////////
                // if they are ALL the same type, a var-arg handler might match
                ///////////////
                if (allSameType) {
                    // do we have a var-arg (it shows as an array) subscribed?

                    ArrayList<Class<?>> superClasses = setupSuperClassCache(firstType);

                    Class<?> eventSuperType = firstType;
                    int j;

                    for (j = -1; j < superClasses.size(); j++) {
                        if (j > -1) {
                            eventSuperType = superClasses.get(j);
                        }
                        if (this.varArgClasses.containsKey(eventSuperType)) {
                            // messy, but the ONLY way to do it.
                            // NOTE: this will NEVER be an array to begin with, since that will call a DIFFERENT method
                            eventSuperType = Array.newInstance(eventSuperType, 1).getClass();

                            // also add all subscriptions that match super types
                            subs = this.subscriptionsPerMessageSingle.get(eventSuperType);
                            if (subs != null) {
                                for (Subscription sub : subs) {
                                    subscriptions.add(sub);
                                }
                            }
                        }
                    }
                }

            }


        } finally {
            this.LOCK.readLock().unlock();
        }

        return subscriptions;
    }


    private final Collection<Class<?>> EMPTY_LIST_CLASSES = Collections.emptyList();
    // must be protected by read lock
    public Collection<Class<?>> getSuperClasses(Class<?> clazz) {
        // not thread safe. DO NOT MODIFY
        ArrayList<Class<?>> types = this.superClassesCache.get(clazz);

        if (types != null) {
            return types;
        }

        return this.EMPTY_LIST_CLASSES;
    }


    // not a thread safe collection. must be locked by caller
    private ArrayList<Class<?>> setupSuperClassCache(Class<?> clazz) {
        ArrayList<Class<?>> types = this.superClassesCache.get(clazz);

        if (types == null) {
            // it doesn't matter if concurrent access stomps on values, since they are always the same.
            Set<Class<?>> superTypes = ReflectionUtils.getSuperTypes(clazz);
            types = new ArrayList<Class<?>>(superTypes);
            // NOTE: no need to write lock, since race conditions will result in duplicate answers
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
            Collection<Subscription> subs = this.subscriptionsPerMessageSingle.get(varArgClass);
            if (subs != null) {
                return subs;
            }
        }

        return this.EMPTY_LIST;
    }

    ///////////////
    // a var-arg handler might match
    // tricky part. We have to check the ARRAY version
    ///////////////
    private void addVarArgClasses(Collection<Subscription> subscriptions, Class<?> messageType, ArrayList<Class<?>> types1) {
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
        ArrayList<Class<?>> superClasses = setupSuperClassCache(classType);

        IdentityObjectTree<Class<?>, Collection<Subscription>> leaf;
        Collection<Subscription> subs;

        Class<?> superClass = classType;
        int i;
        int newIndex;

        for (i = -1; i < superClasses.size(); i++) {
            if (i > -1) {
                superClass = superClasses.get(i);
            }
            leaf = tree.getLeaf(superClass);
            if (leaf != null) {
                newIndex = index+1;
                if (index == length) {
                    subs = leaf.getValue();
                    if (subs != null) {
                        for (Subscription sub : subs) {
                            if (sub.handlesMessageType(messageTypes)) {
                                subscriptions.add(sub);
                            }
                        }
                    }
                } else {
                    getSubsVarArg(subscriptions, length, newIndex, leaf, messageTypes);
                }
            }
        }
    }

    public void readLock() {
        this.LOCK.readLock().lock();
    }

    public void readUnLock() {
        this.LOCK.readLock().unlock();
    }
}
