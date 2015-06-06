package dorkbox.util.messagebus.subscription;

import dorkbox.util.messagebus.common.*;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
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
public final class SubscriptionManager {
    private static final float LOAD_FACTOR = 0.8F;

    private final SubscriptionUtils utils;

    // remember already processed classes that do not contain any message handlers
    private final Map<Class<?>, Boolean> nonListeners;

    // shortcut publication if we know there is no possibility of varArg (ie: a method that has an array as arguments)
    private final AtomicBoolean varArgPossibility = new AtomicBoolean(false);

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


    private final VarArgUtils varArgUtils;

    private final StampedLock lock = new StampedLock();
    private final int numberOfThreads;

    public SubscriptionManager(int numberOfThreads) {
        this.numberOfThreads = numberOfThreads;

        // modified ONLY during SUB/UNSUB
        {
            this.nonListeners = new ConcurrentHashMapV8<Class<?>, Boolean>(4, LOAD_FACTOR, numberOfThreads);

            this.subscriptionsPerMessageSingle = new ConcurrentHashMapV8<Class<?>, ArrayList<Subscription>>(32, LOAD_FACTOR, 1);
            this.subscriptionsPerMessageMulti = new HashMapTree<Class<?>, ArrayList<Subscription>>(4, LOAD_FACTOR);

            // only used during SUB/UNSUB
            this.subscriptionsPerListener = new ConcurrentHashMapV8<Class<?>, Subscription[]>(32, LOAD_FACTOR, 1);
        }

        final SuperClassUtils superClass = new SuperClassUtils(LOAD_FACTOR, 1);
        this.utils = new SubscriptionUtils(superClass, this.subscriptionsPerMessageSingle, this.subscriptionsPerMessageMulti, LOAD_FACTOR,
                                           numberOfThreads);

        // var arg subscriptions keep track of which subscriptions can handle varArgs. SUB/UNSUB dumps it, so it is recreated dynamically.
        // it's a hit on SUB/UNSUB, but improves performance of handlers
        this.varArgUtils = new VarArgUtils(this.utils, superClass, this.subscriptionsPerMessageSingle, LOAD_FACTOR, numberOfThreads);
    }

    public final void shutdown() {
        this.nonListeners.clear();

        this.subscriptionsPerMessageSingle.clear();
        this.subscriptionsPerMessageMulti.clear();
        this.subscriptionsPerListener.clear();

        clearConcurrentCollections();

        this.utils.shutdown();
    }

    public final void subscribe(final Object listener) {
        if (listener == null) {
            return;
        }

        final Class<?> listenerClass = listener.getClass();

        if (this.nonListeners.containsKey(listenerClass)) {
            // early reject of known classes that do not define message handlers
            return;
        }

        // these are concurrent collections
        clearConcurrentCollections();

        Subscription[] subscriptions = getListenerSubs(listenerClass);

        // the subscriptions from the map were null, so create them
        if (subscriptions == null) {
            // it is important to note that this section CAN be repeated, however the write lock is gained before
            // anything 'permanent' is saved. This is so the time spent inside the writelock is minimized.

            final MessageHandler[] messageHandlers = MessageHandler.get(listenerClass);
            final int handlersSize = messageHandlers.length;

            // remember the class as non listening class if no handlers are found
            if (handlersSize == 0) {
                this.nonListeners.put(listenerClass, Boolean.TRUE);
                return;
            }


            final Map<Class<?>, ArrayList<Subscription>> subsPerMessageSingle = this.subscriptionsPerMessageSingle;
            final HashMapTree<Class<?>, ArrayList<Subscription>> subsPerMessageMulti = this.subscriptionsPerMessageMulti;

            final Subscription[] subsPerListener = new Subscription[handlersSize];

            // create the subscription
            MessageHandler messageHandler;
            Subscription subscription;

            for (int i = 0; i < handlersSize; i++) {
                messageHandler = messageHandlers[i];

                // create the subscription
                subscription = new Subscription(messageHandler, LOAD_FACTOR, numberOfThreads);
                subscription.subscribe(listener);

                subsPerListener[i] = subscription; // activates this sub for sub/unsub
            }

            final ConcurrentMap<Class<?>, Subscription[]> subsPerListenerMap = this.subscriptionsPerListener;
            final AtomicBoolean varArgPossibility = this.varArgPossibility;
            final SubscriptionUtils utils = this.utils;

            // now write lock for the least expensive part. This is a deferred "double checked lock", but is necessary because
            // of the huge number of reads compared to writes.

            final StampedLock lock = this.lock;
            final long stamp = lock.writeLock();

            subscriptions = subsPerListenerMap.get(listenerClass);

            // it was still null, so we actually have to create the rest of the subs
            if (subscriptions == null) {
                for (int i = 0; i < handlersSize; i++) {
                    subscription = subsPerListener[i];

                    // now add this subscription to each of the handled types
                    // to activate this sub for publication
                    subscription.registerForPublication(subsPerMessageSingle, subsPerMessageMulti, varArgPossibility, utils);
                }

                subsPerListenerMap.put(listenerClass, subsPerListener);
                lock.unlockWrite(stamp);

                return;
            }
            else {
                // continue to subscription
                lock.unlockWrite(stamp);
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

    public final void unsubscribe(final Object listener) {
        if (listener == null) {
            return;
        }

        final Class<?> listenerClass = listener.getClass();
        if (this.nonListeners.containsKey(listenerClass)) {
            // early reject of known classes that do not define message handlers
            return;
        }

        // these are concurrent collections
        clearConcurrentCollections();

        final Subscription[] subscriptions = getListenerSubs(listenerClass);
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

    private Subscription[] getListenerSubs(final Class<?> listenerClass) {

        final StampedLock lock = this.lock;
        final long stamp = lock.readLock();

        final Subscription[] subscriptions = this.subscriptionsPerListener.get(listenerClass);

        lock.unlockRead(stamp);
        return subscriptions;
    }


    // can return null
    public final Subscription[] getSubscriptionsExact(final Class<?> messageClass) {
        final StampedLock lock = this.lock;
        final long stamp = lock.readLock();

        final ArrayList<Subscription> collection = this.subscriptionsPerMessageSingle.get(messageClass);

        if (collection != null) {
            final Subscription[] subscriptions = new Subscription[collection.size()];
            collection.toArray(subscriptions);

            lock.unlockRead(stamp);
            return subscriptions;
        }

        lock.unlockRead(stamp);
        return null;
    }

    // can return null
    // public because it is also used by unit tests
    public final Subscription[] getSubscriptionsExactAndSuper(final Class<?> messageClass, final boolean isArray) {
        final StampedLock lock = this.lock;
        final long stamp = lock.readLock();

        final Subscription[] subscriptions = getSubscriptionsExactAndSuper_NoLock(messageClass, isArray);

        lock.unlockRead(stamp);
        return subscriptions;
    }

    // can return null
    private Subscription[] getSubscriptionsExactAndSuper_NoLock(final Class<?> messageClass, final boolean isArray) {
        ArrayList<Subscription> collection = this.subscriptionsPerMessageSingle.get(messageClass); // can return null

        // now publish superClasses
        final ArrayList<Subscription> superSubscriptions = this.utils.getSuperSubscriptions(messageClass, isArray); // NOT return null

        if (collection != null) {
            collection = new ArrayList<Subscription>(collection);

            if (!superSubscriptions.isEmpty()) {
                collection.addAll(superSubscriptions);
            }
        }
        else if (!superSubscriptions.isEmpty()) {
                collection = superSubscriptions;
        }

        if (collection != null) {
            final Subscription[] subscriptions = new Subscription[collection.size()];
            collection.toArray(subscriptions);
            return subscriptions;
        }
        else {
            return null;
        }
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

    public final void publishExact(final Object message) throws Throwable {
        final Class<?> messageClass = message.getClass();

        final Subscription[] subscriptions = getSubscriptionsExact(messageClass); // can return null

        // Run subscriptions
        if (subscriptions != null) {
            Subscription sub;
            for (int i = 0; i < subscriptions.length; i++) {
                sub = subscriptions[i];
                sub.publish(message);
            }
        }
        else {
            // Dead Event must EXACTLY MATCH (no subclasses)
            final Subscription[] deadSubscriptions = getSubscriptionsExact(DeadMessage.class); // can return null
            if (deadSubscriptions != null) {
                final DeadMessage deadMessage = new DeadMessage(message);

                Subscription sub;
                for (int i = 0; i < deadSubscriptions.length; i++) {
                    sub = deadSubscriptions[i];
                    sub.publish(deadMessage);
                }
            }
        }
    }

    public final void publishExactAndSuper(final Object message) throws Throwable {
        final Class<?> messageClass = message.getClass();
        final boolean isArray = messageClass.isArray();

        final Subscription[] subscriptions = getSubscriptionsExactAndSuper(messageClass, isArray); // can return null

        // Run subscriptions
        if (subscriptions != null) {
            Subscription sub;
            for (int i = 0; i < subscriptions.length; i++) {
                sub = subscriptions[i];
                sub.publish(message);
            }
        }
        else {
            // Dead Event must EXACTLY MATCH (no subclasses)
            final Subscription[] deadSubscriptions = getSubscriptionsExact(DeadMessage.class); // can return null
            if (deadSubscriptions != null) {
                final DeadMessage deadMessage = new DeadMessage(message);

                Subscription sub;
                for (int i = 0; i < deadSubscriptions.length; i++) {
                    sub = deadSubscriptions[i];
                    sub.publish(deadMessage);
                }
            }
        }
    }

    public final void publishAll(final Object message) throws Throwable {
        final Class<?> messageClass = message.getClass();
        final boolean isArray = messageClass.isArray();

        final StampedLock lock = this.lock;
        long stamp = lock.readLock();

        final Subscription[] subscriptions = getSubscriptionsExactAndSuper_NoLock(messageClass, isArray); // can return null

        lock.unlockRead(stamp);


        // Run subscriptions
        if (subscriptions != null) {
            Subscription sub;
            for (int i = 0; i < subscriptions.length; i++) {
                sub = subscriptions[i];
                sub.publish(message);
            }

            // publish to var arg, only if not already an array
            if (varArgPossibility.get() && !isArray) {
                stamp = lock.readLock();
                final Subscription[] varArgSubscriptions = varArgUtils.getVarArgSubscriptions(messageClass); // can return null
                lock.unlockRead(stamp);

                if (varArgSubscriptions != null) {
                    final int length = varArgSubscriptions.length;
                    Object[] asArray = (Object[]) Array.newInstance(messageClass, 1);
                    asArray[0] = message;


                    for (int i = 0; i < length; i++) {
                        sub = varArgSubscriptions[i];
                        sub.publish(message);
                    }

                    stamp = lock.readLock();
                    // now publish array based superClasses (but only if those ALSO accept vararg)
                    final Subscription[] varArgSuperSubscriptions = this.varArgUtils.getVarArgSuperSubscriptions(messageClass);
                    lock.unlockRead(stamp);

                    if (varArgSuperSubscriptions != null) {
                        for (int i = 0; i < length; i++) {
                            sub = varArgSuperSubscriptions[i];
                            sub.publish(asArray);
                        }
                    }
                }
                else {
                    stamp = lock.readLock();

                    // now publish array based superClasses (but only if those ALSO accept vararg)
                    final Subscription[] varArgSuperSubscriptions = this.varArgUtils.getVarArgSuperSubscriptions(messageClass);
                    lock.unlockRead(stamp);

                    if (varArgSuperSubscriptions != null) {
                        Object[] asArray = (Object[]) Array.newInstance(messageClass, 1);
                        asArray[0] = message;

                        for (int i = 0; i < varArgSuperSubscriptions.length; i++) {
                            sub = varArgSuperSubscriptions[i];
                            sub.publish(asArray);
                        }
                    }
                }
            }
            return;
        }

        // only get here if there were no other subscriptions
        // Dead Event must EXACTLY MATCH (no subclasses)
        final Subscription[] deadSubscriptions = getSubscriptionsExact(DeadMessage.class);
        if (deadSubscriptions != null) {
            final DeadMessage deadMessage = new DeadMessage(message);

            Subscription sub;
            for (int i = 0; i < deadSubscriptions.length; i++) {
                sub = deadSubscriptions[i];
                sub.publish(deadMessage);
            }
        }
    }
}
