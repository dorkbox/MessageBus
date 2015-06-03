package dorkbox.util.messagebus.subscription;

import dorkbox.util.messagebus.common.*;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.StampedLock;

/**
 * The subscription managers responsibility is to consistently handle and synchronize the message listener subscription process.
 * It provides fast lookup of existing subscriptions when another instance of an already known
 * listener is subscribed and takes care of creating new set of subscriptions for any unknown class that defines
 * message handlers.
 * <p>
 * <p>
 * Subscribe/Unsubscribe, while it is possible for them to be 100% concurrent (in relation to listeners per subscription),
 * getting an accurate reflection of the number of subscriptions, or guaranteeing a "HAPPENS-BEFORE" relationship really
 * complicates this, so it has been modified for subscribe/unsubscibe to be mutually exclusive.
 * <p>
 * Given these restrictions and complexity, it is much easier to create a MPSC blocking queue, and have a single thread
 * manage sub/unsub.
 *
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class SubscriptionManager {
    private static final Subscription[] EMPTY = new Subscription[0];

    private static final float LOAD_FACTOR = 0.8F;

    private final SubscriptionUtils utils;

    // remember already processed classes that do not contain any message handlers
    private final Map<Class<?>, Boolean> nonListeners;

    // shortcut publication if we know there is no possibility of varArg (ie: a method that has an array as arguments)
    private AtomicBoolean varArgPossibility = new AtomicBoolean(false);

    // all subscriptions per message type. We perpetually KEEP the types, as this lowers the amount of locking required
    // this is the primary list for dispatching a specific message
    // write access is synchronized and happens only when a listener of a specific class is registered the first time
    private final Map<Class<?>, ArrayList<Subscription>> subscriptionsPerMessageSingle;
    private final HashMapTree<Class<?>, ArrayList<Subscription>> subscriptionsPerMessageMulti;

    // all subscriptions per messageHandler type
    // this map provides fast access for subscribing and unsubscribing
    // write access is synchronized and happens very infrequently
    // once a collection of subscriptions is stored it does not change
    private final ConcurrentMap<Class<?>, Subscription[]> subscriptionsPerListener;


    private final SuperClassUtils superClass;
    private final VarArgUtils varArgUtils;



    private final StampedLock lock = new StampedLock();
//    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public SubscriptionManager(int numberOfThreads) {
        float loadFactor = SubscriptionManager.LOAD_FACTOR;

        // modified ONLY during SUB/UNSUB
        {
            this.nonListeners = new ConcurrentHashMapV8<Class<?>, Boolean>(4, loadFactor, numberOfThreads);

            this.subscriptionsPerMessageSingle = new ConcurrentHashMapV8<Class<?>, ArrayList<Subscription>>(32, LOAD_FACTOR, 1);
            this.subscriptionsPerMessageMulti = new HashMapTree<Class<?>, ArrayList<Subscription>>(4, loadFactor);

            // only used during SUB/UNSUB
            this.subscriptionsPerListener = new ConcurrentHashMapV8<Class<?>, Subscription[]>();
        }

        this.superClass = new SuperClassUtils(loadFactor, 1);
        this.utils = new SubscriptionUtils(superClass, this.subscriptionsPerMessageSingle, this.subscriptionsPerMessageMulti, loadFactor,
                                           numberOfThreads);

        // var arg subscriptions keep track of which subscriptions can handle varArgs. SUB/UNSUB dumps it, so it is recreated dynamically.
        // it's a hit on SUB/UNSUB, but improves performance of handlers
        this.varArgUtils = new VarArgUtils(this.utils, superClass, this.subscriptionsPerMessageSingle, loadFactor, numberOfThreads);
    }

    public void shutdown() {
        this.nonListeners.clear();

        this.subscriptionsPerMessageSingle.clear();
        this.subscriptionsPerMessageMulti.clear();
        this.subscriptionsPerListener.clear();

        this.utils.shutdown();
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

        Subscription[] subscriptions = getListenerSubs(listenerClass);

        // the subscriptions from the map were null, so create them
        if (subscriptions == null) {
            // now write lock for the least expensive part. This is a deferred "double checked lock", but is necessary because
            // of the huge number of reads compared to writes.

            StampedLock lock = this.lock;
            long stamp = lock.writeLock();
//            Lock writeLock = this.lock.writeLock();
//            writeLock.lock();

            ConcurrentMap<Class<?>, Subscription[]> subsPerListener2 = this.subscriptionsPerListener;
            subscriptions = subsPerListener2.get(listenerClass);

            // it was still null, so we actually have to create the rest of the subs
            if (subscriptions == null) {
                MessageHandler[] messageHandlers = MessageHandler.get(listenerClass);
                int handlersSize = messageHandlers.length;

                // remember the class as non listening class if no handlers are found
                if (handlersSize == 0) {
                    this.nonListeners.put(listenerClass, Boolean.TRUE);
                    lock.unlockWrite(stamp);
//                    writeLock.unlock();
                    return;
                }

                Map<Class<?>, ArrayList<Subscription>> subsPerMessageSingle = this.subscriptionsPerMessageSingle;
                HashMapTree<Class<?>, ArrayList<Subscription>> subsPerMessageMulti = this.subscriptionsPerMessageMulti;

                ArrayList<Subscription> subsPerListener = new ArrayList<Subscription>(handlersSize);
                Collection<Subscription> subsForPublication = null;

                // create the subscription
                MessageHandler messageHandler;

                for (int i = 0; i < handlersSize; i++) {
                    messageHandler = messageHandlers[i];

                    // create the subscription
                    Subscription subscription = new Subscription(messageHandler);
                    subscription.subscribe(listener);

                    subsPerListener.add(subscription); // activates this sub for sub/unsub

                    // now add this subscription to each of the handled types
                    subsForPublication = getSubsForPublication(messageHandler.getHandledMessages(), subsPerMessageSingle,
                                                               subsPerMessageMulti);
                    subsForPublication.add(subscription);  // activates this sub for publication
                }

                subsPerListener2.put(listenerClass, subsPerListener.toArray(new Subscription[subsPerListener.size()]));
                lock.unlockWrite(stamp);
//                writeLock.unlock();

                return;
            }
            else {
                lock.unlockWrite(stamp);
//                writeLock.unlock();
            }
        }

        // subscriptions already exist and must only be updated
        // only publish here if our single-check was OK, or our double-check was OK
        Subscription subscription;

        for (int i = 0; i < subscriptions.length; i++) {
            subscription = subscriptions[i];
            subscription.subscribe(listener);
        }
    }

    // inside a write lock
    // also puts it into the correct map if it's not already there
    private Collection<Subscription> getSubsForPublication(final Class<?>[] messageHandlerTypes,
                                                           final Map<Class<?>, ArrayList<Subscription>> subsPerMessageSingle,
                                                           final HashMapTree<Class<?>, ArrayList<Subscription>> subsPerMessageMulti) {

        final int size = messageHandlerTypes.length;

//        ConcurrentSet<Subscription> subsPerType;

        SubscriptionUtils utils = this.utils;
        Class<?> type0 = messageHandlerTypes[0];

        switch (size) {
            case 1: {
                ArrayList<Subscription> subs = subsPerMessageSingle.get(type0);
                if (subs == null) {
                    subs = new ArrayList<Subscription>();

                    boolean isArray = type0.isArray();
                    if (isArray) {
                        varArgPossibility.lazySet(true);
                    }

                    // cache the super classes
                    //utils.cacheSuperClasses(type0, isArray);

                    subsPerMessageSingle.put(type0, subs);
                }

                return subs;
            }
            case 2: {
                // the HashMapTree uses read/write locks, so it is only accessible one thread at a time
//                SubscriptionHolder subHolderSingle = this.subHolderSingle;
//                subsPerType = subHolderSingle.publish();
//
//                Collection<Subscription> putIfAbsent = subsPerMessageMulti.putIfAbsent(subsPerType, type0, types[1]);
//                if (putIfAbsent != null) {
//                    return putIfAbsent;
//                } else {
//                    subHolderSingle.set(subHolderSingle.initialValue());
//
//                    // cache the super classes
//                    utils.cacheSuperClasses(type0);
//                    utils.cacheSuperClasses(types[1]);
//
//                    return subsPerType;
//                }
            }
            case 3: {
                // the HashMapTree uses read/write locks, so it is only accessible one thread at a time
//                SubscriptionHolder subHolderSingle = this.subHolderSingle;
//                subsPerType = subHolderSingle.publish();
//
//                Collection<Subscription> putIfAbsent = subsPerMessageMulti.putIfAbsent(subsPerType, type0, types[1], types[2]);
//                if (putIfAbsent != null) {
//                    return putIfAbsent;
//                } else {
//                    subHolderSingle.set(subHolderSingle.initialValue());
//
//                    // cache the super classes
//                    utils.cacheSuperClasses(type0);
//                    utils.cacheSuperClasses(types[1]);
//                    utils.cacheSuperClasses(types[2]);
//
//                    return subsPerType;
//                }
            }
            default: {
                // the HashMapTree uses read/write locks, so it is only accessible one thread at a time
//                SubscriptionHolder subHolderSingle = this.subHolderSingle;
//                subsPerType = subHolderSingle.publish();
//
//                Collection<Subscription> putIfAbsent = subsPerMessageMulti.putIfAbsent(subsPerType, types);
//                if (putIfAbsent != null) {
//                    return putIfAbsent;
//                } else {
//                    subHolderSingle.set(subHolderSingle.initialValue());
//
//                    Class<?> c;
//                    int length = types.length;
//                    for (int i = 0; i < length; i++) {
//                        c = types[i];
//
//                        // cache the super classes
//                        utils.cacheSuperClasses(c);
//                    }
//
//                    return subsPerType;
//                }
                return null;
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

        Subscription[] subscriptions = getListenerSubs(listenerClass);
        if (subscriptions != null) {
            Subscription subscription;

            for (int i = 0; i < subscriptions.length; i++) {
                subscription = subscriptions[i];
                subscription.unsubscribe(listener);
            }
        }
    }

    private void clearConcurrentCollections() {
        this.utils.clear();
        this.varArgUtils.clear();
    }

    private Subscription[] getListenerSubs(Class<?> listenerClass) {
        Subscription[] subscriptions;

        StampedLock lock = this.lock;
        long stamp = lock.readLock();
//        Lock readLock = this.lock.readLock();
//        readLock.lock();

        subscriptions = this.subscriptionsPerListener.get(listenerClass);

        lock.unlockRead(stamp);
//        readLock.unlock();

        return subscriptions;
    }


    // retrieves all of the appropriate subscriptions for the message type
    public final Subscription[] getSubscriptionsForcedExact(final Class<?> messageClass) {
        ArrayList<Subscription> collection;
        Subscription[] subscriptions;

        StampedLock lock = this.lock;
        long stamp = lock.readLock();
//        Lock readLock = this.lock.readLock();
//        readLock.lock();

        collection = this.subscriptionsPerMessageSingle.get(messageClass);
//
        if (collection != null) {
            subscriptions = collection.toArray(new Subscription[collection.size()]);
        }
        else {
//        subscriptions = EMPTY;
            subscriptions = null;
        }

        lock.unlockRead(stamp);
//        readLock.unlock();
        return subscriptions;
    }

    // never return null
    public final Subscription[] getSubscriptions(final Class<?> messageClass, boolean isArray) {
        StampedLock lock = this.lock;
        long stamp = lock.readLock();
//        Lock readLock = this.lock.readLock();
//        readLock.lock();

        final Subscription[] subscriptions = getSubscriptions_NL(messageClass, isArray);

        lock.unlockRead(stamp);
//        readLock.unlock();

        return subscriptions;
    }

    // never return null
    private Subscription[] getSubscriptions_NL(final Class<?> messageClass, boolean isArray) {
        ArrayList<Subscription> collection;

        collection = this.subscriptionsPerMessageSingle.get(messageClass); // can return null

        // now publish superClasses
        ArrayList<Subscription> superSubscriptions = this.utils.getSuperSubscriptions(messageClass, isArray); // NOT return null

        if (collection != null) {
            collection = new ArrayList<Subscription>(collection);
            if (!superSubscriptions.isEmpty()) {
                collection.addAll(superSubscriptions);
            }
        }
        else {
            if (!superSubscriptions.isEmpty()) {
                collection = superSubscriptions;
            }
        }

        final Subscription[] subscriptions;
        if (collection != null) {
            subscriptions = collection.toArray(new Subscription[collection.size()]);
        }
        else {
            subscriptions = null;
        }

        return subscriptions;
    }


    // CAN RETURN NULL
//    public final Subscription[] getSubscriptionsByMessageType(final Class<?> messageType) {
//        Collection<Subscription> collection;
//        Subscription[] subscriptions = null;
//
////        long stamp = this.lock.readLock();
//        Lock readLock = this.lock.readLock();
//        readLock.lock();
//
//        try {
//            collection = this.subscriptionsPerMessageSingle.publish(messageType);
//            if (collection != null) {
//                subscriptions = collection.toArray(EMPTY);
//            }
//        }
//        finally {
////            this.lock.unlockRead(stamp);
//            readLock.unlock();
//        }
//
//
////        long stamp = this.lock.tryOptimisticRead(); // non blocking
////
////        collection = this.subscriptionsPerMessageSingle.publish(messageType);
////        if (collection != null) {
//////            subscriptions = new ArrayDeque<>(collection);
////            subscriptions = new ArrayList<>(collection);
//////            subscriptions = new LinkedList<>();
//////            subscriptions = new TreeSet<Subscription>(SubscriptionByPriorityDesc);
////
//////            subscriptions.addAll(collection);
////        }
////
////        if (!this.lock.validate(stamp)) { // if a write occurred, try again with a read lock
////            stamp = this.lock.readLock();
////            try {
////                collection = this.subscriptionsPerMessageSingle.publish(messageType);
////                if (collection != null) {
//////                    subscriptions = new ArrayDeque<>(collection);
////                    subscriptions = new ArrayList<>(collection);
//////                    subscriptions = new LinkedList<>();
//////                    subscriptions = new TreeSet<Subscription>(SubscriptionByPriorityDesc);
////
//////                    subscriptions.addAll(collection);
////                }
////            }
////            finally {
////                this.lock.unlockRead(stamp);
////            }
////        }
//
//        return subscriptions;
//    }

//    public static final Comparator<Subscription> SubscriptionByPriorityDesc = new Comparator<Subscription>() {
//        @Override
//        public int compare(Subscription o1, Subscription o2) {
////            int byPriority = ((Integer)o2.getPriority()).compareTo(o1.getPriority());
////            return byPriority == 0 ? o2.id.compareTo(o1.id) : byPriority;
//            if (o2.ID > o1.ID) {
//                return 1;
//            } else if (o2.ID < o1.ID) {
//                return -1;
//            } else {
//                return 0;
//            }
//        }
//    };


    // CAN RETURN NULL
    public final Collection<Subscription> getSubscriptionsByMessageType(Class<?> messageType1, Class<?> messageType2) {
        return this.subscriptionsPerMessageMulti.get(messageType1, messageType2);
    }


    // CAN RETURN NULL
    public final Collection<Subscription> getSubscriptionsByMessageType(Class<?> messageType1, Class<?> messageType2,
                                                                        Class<?> messageType3) {
        return this.subscriptionsPerMessageMulti.getValue(messageType1, messageType2, messageType3);
    }

    // CAN RETURN NULL
    public final Collection<Subscription> getSubscriptionsByMessageType(Class<?>... messageTypes) {
        return this.subscriptionsPerMessageMulti.get(messageTypes);
    }


    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public Collection<Subscription> getVarArgSuperSubscriptions(Class<?> messageClass) {
        return this.varArgUtils.getVarArgSuperSubscriptions(messageClass);
    }

    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public Collection<Subscription> getVarArgSuperSubscriptions(Class<?> messageClass1, Class<?> messageClass2) {
        return this.varArgUtils.getVarArgSuperSubscriptions(messageClass1, messageClass2);
    }

    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public Collection<Subscription> getVarArgSuperSubscriptions(final Class<?> messageClass1, final Class<?> messageClass2,
                                                                final Class<?> messageClass3) {
        return this.varArgUtils.getVarArgSuperSubscriptions(messageClass1, messageClass2, messageClass3);
    }


//    // CAN NOT RETURN NULL
//    // ALSO checks to see if the superClass accepts subtypes.
//    public final Subscription[] getSuperSubscriptions(Class<?> superType) {
//        return this.utils.getSuperSubscriptions(superType);
//    }

    // CAN NOT RETURN NULL
    // ALSO checks to see if the superClass accepts subtypes.
    public Collection<Subscription> getSuperSubscriptions(Class<?> superType1, Class<?> superType2) {
        return this.utils.getSuperSubscriptions(superType1, superType2);
    }

    // CAN NOT RETURN NULL
    // ALSO checks to see if the superClass accepts subtypes.
    public Collection<Subscription> getSuperSubscriptions(Class<?> superType1, Class<?> superType2, Class<?> superType3) {
        return this.utils.getSuperSubscriptions(superType1, superType2, superType3);
    }

    /**
     * @return true if subscriptions were published
     */
    public boolean publishExact(Object message) throws Throwable {
        final Class<?> messageClass = message.getClass();
        final boolean isArray = messageClass.isArray();

        StampedLock lock = this.lock;
        long stamp = lock.readLock();

        final Subscription[] subscriptions = getSubscriptions_NL(messageClass, isArray);
        Subscription sub;

        // Run subscriptions
        if (subscriptions != null) {
            for (int i = 0; i < subscriptions.length; i++) {
                sub = subscriptions[i];
                sub.publish(message);
            }

            lock.unlockRead(stamp);
            return true;
        }

        lock.unlockRead(stamp);
        return false;
    }

    /**
     * @return true if subscriptions were published
     */
    public boolean publish(Object message) throws Throwable {
        final Class<?> messageClass = message.getClass();
        final boolean isArray = messageClass.isArray();

        StampedLock lock = this.lock;
        long stamp = lock.readLock();


        final Subscription[] subscriptions = getSubscriptions_NL(messageClass, isArray);
        Subscription sub;

        // Run subscriptions
        if (subscriptions != null) {
            for (int i = 0; i < subscriptions.length; i++) {
                sub = subscriptions[i];
                sub.publish(message);
            }

            // publish to var arg, only if not already an array
            if (varArgPossibility.get() && !isArray) {
                final ArrayList<Subscription> varArgSubscriptions = varArgUtils.getVarArgSubscriptions(messageClass);

                if (varArgSubscriptions != null && !varArgSubscriptions.isEmpty()) {
                    Object[] asArray = (Object[]) Array.newInstance(messageClass, 1);
                    asArray[0] = message;

                    Iterator<Subscription> iterator;
                    for (iterator = varArgSubscriptions.iterator(); iterator.hasNext(); ) {
                        sub = iterator.next();

                        sub.publish(asArray);
                    }

                    // now publish array based superClasses (but only if those ALSO accept vararg)
                    final Collection<Subscription> varArgSuperSubscriptions = getVarArgSuperSubscriptions(messageClass);
                    if (varArgSuperSubscriptions != null && !varArgSuperSubscriptions.isEmpty()) {
                        for (iterator = varArgSubscriptions.iterator(); iterator.hasNext(); ) {
                            sub = iterator.next();

                            sub.publish(asArray);
                        }
                    }
                }
                else {
                    // now publish array based superClasses (but only if those ALSO accept vararg)
                    final Collection<Subscription> varArgSuperSubscriptions = getVarArgSuperSubscriptions(messageClass);
                    if (varArgSuperSubscriptions != null && !varArgSuperSubscriptions.isEmpty()) {
                        Object[] asArray = (Object[]) Array.newInstance(messageClass, 1);
                        asArray[0] = message;

                        Iterator<Subscription> iterator;
                        for (iterator = varArgSuperSubscriptions.iterator(); iterator.hasNext(); ) {
                            sub = iterator.next();

                            sub.publish(asArray);
                        }
                    }
                }
            }

            lock.unlockRead(stamp);
            return true;
        }

        lock.unlockRead(stamp);
        return false;
    }
}
