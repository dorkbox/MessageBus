package dorkbox.util.messagebus.subscription;

import dorkbox.util.messagebus.common.*;

import java.lang.reflect.Array;
import java.util.ArrayList;
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

    private final ClassUtils classUtils;
    private final SubscriptionUtils subUtils;
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

        classUtils = new ClassUtils(LOAD_FACTOR);

        this.subUtils = new SubscriptionUtils(classUtils, this.subscriptionsPerMessageSingle, this.subscriptionsPerMessageMulti,
                                              LOAD_FACTOR, numberOfThreads);

        // var arg subscriptions keep track of which subscriptions can handle varArgs. SUB/UNSUB dumps it, so it is recreated dynamically.
        // it's a hit on SUB/UNSUB, but improves performance of handlers
        this.varArgUtils = new VarArgUtils(classUtils, this.subscriptionsPerMessageSingle, LOAD_FACTOR, numberOfThreads);
    }

    public void shutdown() {
        this.nonListeners.clear();

        this.subscriptionsPerMessageSingle.clear();
        this.subscriptionsPerMessageMulti.clear();
        this.subscriptionsPerListener.clear();

        clearConcurrentCollections();

        this.classUtils.clear();
    }

    public void subscribe(final Object listener) {
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
                    subscription.registerForPublication(subsPerMessageSingle, subsPerMessageMulti, varArgPossibility);
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

    public void unsubscribe(final Object listener) {
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
        this.subUtils.clear();
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
    public Subscription[] getSubscriptionsExact(final Class<?> messageClass) {
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
    public Subscription[] getSubscriptionsExact(final Class<?> messageClass1, final Class<?> messageClass2) {
        final StampedLock lock = this.lock;
        final long stamp = lock.readLock();

        final ArrayList<Subscription> collection = this.subscriptionsPerMessageMulti.get(messageClass1, messageClass2);

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
    public Subscription[] getSubscriptionsExact(final Class<?> messageClass1, final Class<?> messageClass2, final Class<?> messageClass3) {
        final StampedLock lock = this.lock;
        final long stamp = lock.readLock();

        final ArrayList<Subscription> collection = this.subscriptionsPerMessageMulti.get(messageClass1, messageClass2, messageClass3);

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
    public Subscription[] getSubscriptionsExactAndSuper(final Class<?> messageClass) {
        final StampedLock lock = this.lock;
        final long stamp = lock.readLock();

        final Subscription[] subscriptions = getSubscriptionsExactAndSuper_NoLock(messageClass);

        lock.unlockRead(stamp);
        return subscriptions;
    }

    // can return null
    private Subscription[] getSubscriptionsExactAndSuper(final Class<?> messageClass1, final Class<?> messageClass2) {
        final StampedLock lock = this.lock;
        final long stamp = lock.readLock();

        final Subscription[] subscriptions = getSubscriptionsExactAndSuper_NoLock(messageClass1, messageClass2);

        lock.unlockRead(stamp);
        return subscriptions;
    }

    // can return null
    private Subscription[] getSubscriptionsExactAndSuper(final Class<?> messageClass1, final Class<?> messageClass2,
                                                         final Class<?> messageClass3) {
        final StampedLock lock = this.lock;
        final long stamp = lock.readLock();

        final Subscription[] subscriptions = getSubscriptionsExactAndSuper_NoLock(messageClass1, messageClass2, messageClass3);

        lock.unlockRead(stamp);
        return subscriptions;
    }


    // can return null
    private Subscription[] getSubscriptionsExactAndSuper_NoLock(final Class<?> messageClass) {
        ArrayList<Subscription> collection = this.subscriptionsPerMessageSingle.get(messageClass); // can return null

        // now publish superClasses
        final ArrayList<Subscription> superSubscriptions = this.subUtils.getSuperSubscriptions(messageClass); // NOT return null

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

    // can return null
    private Subscription[] getSubscriptionsExactAndSuper_NoLock(final Class<?> messageClass1, final Class<?> messageClass2) {

        ArrayList<Subscription> collection = this.subscriptionsPerMessageMulti.get(messageClass1, messageClass2); // can return null

        // now publish superClasses
        final ArrayList<Subscription> superSubs = this.subUtils.getSuperSubscriptions(messageClass1, messageClass2); // NOT return null

        if (collection != null) {
            collection = new ArrayList<Subscription>(collection);

            if (!superSubs.isEmpty()) {
                collection.addAll(superSubs);
            }
        }
        else if (!superSubs.isEmpty()) {
            collection = superSubs;
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

    // can return null
    private Subscription[] getSubscriptionsExactAndSuper_NoLock(final Class<?> messageClass1, final Class<?> messageClass2,
                                                                final Class<?> messageClass3) {

        ArrayList<Subscription> collection = this.subscriptionsPerMessageMulti
                        .get(messageClass1, messageClass2, messageClass3); // can return null

        // now publish superClasses
        final ArrayList<Subscription> superSubs = this.subUtils
                        .getSuperSubscriptions(messageClass1, messageClass2, messageClass3); // NOT return null

        if (collection != null) {
            collection = new ArrayList<Subscription>(collection);

            if (!superSubs.isEmpty()) {
                collection.addAll(superSubs);
            }
        }
        else if (!superSubs.isEmpty()) {
            collection = superSubs;
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

    public void publishExact(final Object message) throws Throwable {
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

    public void publishExact(final Object message1, final Object message2) throws Throwable {
        final Class<?> messageClass1 = message1.getClass();
        final Class<?> messageClass2 = message2.getClass();

        final Subscription[] subscriptions = getSubscriptionsExact(messageClass1, messageClass2); // can return null

        // Run subscriptions
        if (subscriptions != null) {
            Subscription sub;
            for (int i = 0; i < subscriptions.length; i++) {
                sub = subscriptions[i];
                sub.publish(message1, message2);
            }
        }
        else {
            // Dead Event must EXACTLY MATCH (no subclasses)
            final Subscription[] deadSubscriptions = getSubscriptionsExact(DeadMessage.class); // can return null
            if (deadSubscriptions != null) {
                final DeadMessage deadMessage = new DeadMessage(message1, message2);

                Subscription sub;
                for (int i = 0; i < deadSubscriptions.length; i++) {
                    sub = deadSubscriptions[i];
                    sub.publish(deadMessage);
                }
            }
        }
    }

    public void publishExact(final Object message1, final Object message2, final Object message3) throws Throwable {
        final Class<?> messageClass1 = message1.getClass();
        final Class<?> messageClass2 = message2.getClass();
        final Class<?> messageClass3 = message3.getClass();

        final Subscription[] subscriptions = getSubscriptionsExact(messageClass1, messageClass2, messageClass3); // can return null

        // Run subscriptions
        if (subscriptions != null) {
            Subscription sub;
            for (int i = 0; i < subscriptions.length; i++) {
                sub = subscriptions[i];
                sub.publish(message1, message2, message3);
            }
        }
        else {
            // Dead Event must EXACTLY MATCH (no subclasses)
            final Subscription[] deadSubscriptions = getSubscriptionsExact(DeadMessage.class); // can return null
            if (deadSubscriptions != null) {
                final DeadMessage deadMessage = new DeadMessage(message1, message2, message3);

                Subscription sub;
                for (int i = 0; i < deadSubscriptions.length; i++) {
                    sub = deadSubscriptions[i];
                    sub.publish(deadMessage);
                }
            }
        }
    }



    public void publishExactAndSuper(final Object message) throws Throwable {
        final Class<?> messageClass = message.getClass();

        final Subscription[] subscriptions = getSubscriptionsExactAndSuper(messageClass); // can return null

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

    public void publishExactAndSuper(final Object message1, final Object message2) throws Throwable {
        final Class<?> messageClass1 = message1.getClass();
        final Class<?> messageClass2 = message2.getClass();

        final Subscription[] subscriptions = getSubscriptionsExactAndSuper(messageClass1, messageClass2); // can return null

        // Run subscriptions
        if (subscriptions != null) {
            Subscription sub;
            for (int i = 0; i < subscriptions.length; i++) {
                sub = subscriptions[i];
                sub.publish(message1, message2);
            }
        }
        else {
            // Dead Event must EXACTLY MATCH (no subclasses)
            final Subscription[] deadSubscriptions = getSubscriptionsExact(DeadMessage.class); // can return null
            if (deadSubscriptions != null) {
                final DeadMessage deadMessage = new DeadMessage(message1, message2);

                Subscription sub;
                for (int i = 0; i < deadSubscriptions.length; i++) {
                    sub = deadSubscriptions[i];
                    sub.publish(deadMessage);
                }
            }
        }
    }

    public void publishExactAndSuper(final Object message1, final Object message2, final Object message3) throws Throwable {
        final Class<?> messageClass1 = message1.getClass();
        final Class<?> messageClass2 = message2.getClass();
        final Class<?> messageClass3 = message3.getClass();

        final Subscription[] subscriptions = getSubscriptionsExactAndSuper(messageClass1, messageClass2, messageClass3); // can return null

        // Run subscriptions
        if (subscriptions != null) {
            Subscription sub;
            for (int i = 0; i < subscriptions.length; i++) {
                sub = subscriptions[i];
                sub.publish(message1, message2, message3);
            }
        }
        else {
            // Dead Event must EXACTLY MATCH (no subclasses)
            final Subscription[] deadSubscriptions = getSubscriptionsExact(DeadMessage.class); // can return null
            if (deadSubscriptions != null) {
                final DeadMessage deadMessage = new DeadMessage(message1, message2, message3);

                Subscription sub;
                for (int i = 0; i < deadSubscriptions.length; i++) {
                    sub = deadSubscriptions[i];
                    sub.publish(deadMessage);
                }
            }
        }
    }



    public void publishAll(final Object message) throws Throwable {
        final Class<?> messageClass = message.getClass();
        final boolean isArray = messageClass.isArray();

        final StampedLock lock = this.lock;
        long stamp = lock.readLock();
        final Subscription[] subscriptions = getSubscriptionsExactAndSuper_NoLock(messageClass); // can return null
        lock.unlockRead(stamp);

        boolean hasSubs = false;
        // Run subscriptions
        if (subscriptions != null) {
            hasSubs = true;

            Subscription sub;
            for (int i = 0; i < subscriptions.length; i++) {
                sub = subscriptions[i];
                sub.publish(message);
            }
        }


        // publish to var arg, only if not already an array (because that would be unnecessary)
        if (varArgPossibility.get() && !isArray) {
            stamp = lock.readLock();
            final Subscription[] varArgSubs = varArgUtils.getVarArgSubscriptions(messageClass); // CAN NOT RETURN NULL
            lock.unlockRead(stamp);

            Subscription sub;
            int length = varArgSubs.length;
            Object[] asArray = null;

            if (length > 1) {
                hasSubs = true;

                asArray = (Object[]) Array.newInstance(messageClass, 1);
                asArray[0] = message;

                for (int i = 0; i < length; i++) {
                    sub = varArgSubs[i];
                    sub.publish(asArray);
                }
            }


            // now publish array based superClasses (but only if those ALSO accept vararg)
            stamp = lock.readLock();
            final Subscription[] varArgSuperSubs = this.varArgUtils.getVarArgSuperSubscriptions(messageClass);// CAN NOT RETURN NULL
            lock.unlockRead(stamp);

            length = varArgSuperSubs.length;

            if (length > 1) {
                hasSubs = true;

                if (asArray == null) {
                    asArray = (Object[]) Array.newInstance(messageClass, 1);
                    asArray[0] = message;
                }

                for (int i = 0; i < length; i++) {
                    sub = varArgSuperSubs[i];
                    sub.publish(asArray);
                }
            }
        }

        // only get here if there were no other subscriptions
        // Dead Event must EXACTLY MATCH (no subclasses)
        if (!hasSubs) {
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

    public void publishAll(final Object message1, final Object message2) throws Throwable {
        final Class<?> messageClass1 = message1.getClass();
        final Class<?> messageClass2 = message2.getClass();

        final StampedLock lock = this.lock;
        long stamp = lock.readLock();
        final Subscription[] subscriptions = getSubscriptionsExactAndSuper_NoLock(messageClass1, messageClass2); // can return null
        lock.unlockRead(stamp);

        boolean hasSubs = false;
        // Run subscriptions
        if (subscriptions != null) {
            hasSubs = true;

            Subscription sub;
            for (int i = 0; i < subscriptions.length; i++) {
                sub = subscriptions[i];
                sub.publish(message1, message2);
            }
        }

        // publish to var arg, only if not already an array AND we are all of the same type
        if (varArgPossibility.get() && !messageClass1.isArray() && !messageClass2.isArray()) {

            // vararg can ONLY work if all types are the same
            if (messageClass1 == messageClass2) {
                stamp = lock.readLock();
                final Subscription[] varArgSubs = varArgUtils.getVarArgSubscriptions(messageClass1); // can NOT return null
                lock.unlockRead(stamp);

                final int length = varArgSubs.length;
                if (length > 0) {
                    hasSubs = true;

                    Object[] asArray = (Object[]) Array.newInstance(messageClass1, 2);
                    asArray[0] = message1;
                    asArray[1] = message2;

                    Subscription sub;
                    for (int i = 0; i < length; i++) {
                        sub = varArgSubs[i];
                        sub.publish(asArray);
                    }
                }
            }

            // now publish array based superClasses (but only if those ALSO accept vararg)
            stamp = lock.readLock();
            final Subscription[] varArgSuperSubs = this.varArgUtils
                            .getVarArgSuperSubscriptions(messageClass1, messageClass2); // CAN NOT RETURN NULL
            lock.unlockRead(stamp);


            final int length = varArgSuperSubs.length;
            if (length > 0) {
                hasSubs = true;

                Class<?> arrayType;
                Object[] asArray;

                Subscription sub;
                for (int i = 0; i < length; i++) {
                    sub = varArgSuperSubs[i];
                    arrayType = sub.getHandler().getVarArgClass();

                    asArray = (Object[]) Array.newInstance(arrayType, 2);
                    asArray[0] = message1;
                    asArray[1] = message2;

                    sub.publish(asArray);
                }
            }
        }

        if (!hasSubs) {
            // Dead Event must EXACTLY MATCH (no subclasses)
            final Subscription[] deadSubscriptions = getSubscriptionsExact(DeadMessage.class); // can return null
            if (deadSubscriptions != null) {
                final DeadMessage deadMessage = new DeadMessage(message1, message2);

                Subscription sub;
                for (int i = 0; i < deadSubscriptions.length; i++) {
                    sub = deadSubscriptions[i];
                    sub.publish(deadMessage);
                }
            }
        }
    }

    public void publishAll(final Object message1, final Object message2, final Object message3) throws Throwable {
        final Class<?> messageClass1 = message1.getClass();
        final Class<?> messageClass2 = message2.getClass();
        final Class<?> messageClass3 = message3.getClass();

        final StampedLock lock = this.lock;
        long stamp = lock.readLock();
        final Subscription[] subs = getSubscriptionsExactAndSuper_NoLock(messageClass1, messageClass2, messageClass3); // can return null
        lock.unlockRead(stamp);


        boolean hasSubs = false;
        // Run subscriptions
        if (subs != null) {
            hasSubs = true;

            Subscription sub;
            for (int i = 0; i < subs.length; i++) {
                sub = subs[i];
                sub.publish(message1, message2, message3);
            }
        }

        // publish to var arg, only if not already an array AND we are all of the same type
        if (varArgPossibility.get() && !messageClass1.isArray() && !messageClass2.isArray() && !messageClass3.isArray()) {

            // vararg can ONLY work if all types are the same
            if (messageClass1 == messageClass2 && messageClass1 == messageClass3) {
                stamp = lock.readLock();
                final Subscription[] varArgSubs = varArgUtils.getVarArgSubscriptions(messageClass1); // can NOT return null
                lock.unlockRead(stamp);

                final int length = varArgSubs.length;
                if (length > 0) {
                    hasSubs = true;

                    Object[] asArray = (Object[]) Array.newInstance(messageClass1, 3);
                    asArray[0] = message1;
                    asArray[1] = message2;
                    asArray[2] = message3;

                    Subscription sub;
                    for (int i = 0; i < length; i++) {
                        sub = varArgSubs[i];
                        sub.publish(asArray);
                    }
                }
            }


            // now publish array based superClasses (but only if those ALSO accept vararg)
            stamp = lock.readLock();
            final Subscription[] varArgSuperSubs = this.varArgUtils
                            .getVarArgSuperSubscriptions(messageClass1, messageClass2, messageClass3); // CAN NOT RETURN NULL
            lock.unlockRead(stamp);


            final int length = varArgSuperSubs.length;
            if (length > 0) {
                hasSubs = true;

                Class<?> arrayType;
                Object[] asArray;

                Subscription sub;
                for (int i = 0; i < length; i++) {
                    sub = varArgSuperSubs[i];
                    arrayType = sub.getHandler().getVarArgClass();

                    asArray = (Object[]) Array.newInstance(arrayType, 3);
                    asArray[0] = message1;
                    asArray[1] = message2;
                    asArray[2] = message3;

                    sub.publish(asArray);
                }
            }
        }

        if (!hasSubs) {
            // Dead Event must EXACTLY MATCH (no subclasses)
            final Subscription[] deadSubscriptions = getSubscriptionsExact(DeadMessage.class); // can return null
            if (deadSubscriptions != null) {
                final DeadMessage deadMessage = new DeadMessage(message1, message2, message3);

                Subscription sub;
                for (int i = 0; i < deadSubscriptions.length; i++) {
                    sub = deadSubscriptions[i];
                    sub.publish(deadMessage);
                }
            }
        }
    }


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


}
