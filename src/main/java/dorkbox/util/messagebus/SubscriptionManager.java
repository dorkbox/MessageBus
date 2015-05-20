package dorkbox.util.messagebus;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import dorkbox.util.messagebus.common.ConcurrentHashMapV8;
import dorkbox.util.messagebus.common.HashMapTree;
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
    private final ConcurrentMap<Class<?>, Collection<Subscription>> subscriptionsPerMessageSingle;
    private final HashMapTree<Class<?>, Collection<Subscription>> subscriptionsPerMessageMulti;

    // all subscriptions per messageHandler type
    // this map provides fast access for subscribing and unsubscribing
    // write access is synchronized and happens very infrequently
    // once a collection of subscriptions is stored it does not change
    private final ConcurrentMap<Class<?>, ConcurrentSet<Subscription>> subscriptionsPerListener;


    private final VarArgUtils varArgUtils;

    // stripe size of maps for concurrency
    private final int STRIPE_SIZE;

    private final SubscriptionHolder subHolderSingle;
    private final SubscriptionHolder subHolderConcurrent;


    SubscriptionManager(int numberOfThreads) {
        this.STRIPE_SIZE = numberOfThreads;

        float loadFactor = SubscriptionManager.LOAD_FACTOR;

        // modified ONLY during SUB/UNSUB
        {
            this.nonListeners = new ConcurrentHashMapV8<Class<?>, Boolean>(4, loadFactor, this.STRIPE_SIZE);

            this.subscriptionsPerMessageSingle = new ConcurrentHashMapV8<Class<?>, Collection<Subscription>>(4, loadFactor, this.STRIPE_SIZE);
            this.subscriptionsPerMessageMulti = new HashMapTree<Class<?>, Collection<Subscription>>(4, loadFactor);

            // only used during SUB/UNSUB
            this.subscriptionsPerListener = new ConcurrentHashMapV8<Class<?>, ConcurrentSet<Subscription>>(4, loadFactor, this.STRIPE_SIZE);
        }

        this.utils = new SubscriptionUtils(this.subscriptionsPerMessageSingle, this.subscriptionsPerMessageMulti, loadFactor, numberOfThreads);

        // var arg subscriptions keep track of which subscriptions can handle varArgs. SUB/UNSUB dumps it, so it is recreated dynamically.
        // it's a hit on SUB/UNSUB, but improves performance of handlers
        this.varArgUtils = new VarArgUtils(this.utils, this.subscriptionsPerMessageSingle, loadFactor, this.STRIPE_SIZE);

        this.subHolderSingle = new SubscriptionHolder(loadFactor, this.STRIPE_SIZE);
        this.subHolderConcurrent = new SubscriptionHolder(loadFactor, this.STRIPE_SIZE);
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
                Collection<MessageHandler> messageHandlers = SubscriptionManager.metadataReader.getMessageListener(listenerClass, LOAD_FACTOR, this.STRIPE_SIZE).getHandlers();
                int handlersSize = messageHandlers.size();

                if (handlersSize == 0) {
                    // remember the class as non listening class if no handlers are found
                    this.nonListeners.put(listenerClass, Boolean.TRUE);
                    return;
                } else {
                    subsPerListener = new ConcurrentSet<Subscription>(16, SubscriptionManager.LOAD_FACTOR, this.STRIPE_SIZE);

                    VarArgPossibility varArgPossibility = this.varArgPossibility;
                    ConcurrentMap<Class<?>, Collection<Subscription>> subsPerMessageSingle = this.subscriptionsPerMessageSingle;
                    HashMapTree<Class<?>, Collection<Subscription>> subsPerMessageMulti = this.subscriptionsPerMessageMulti;


                    Iterator<MessageHandler> iterator;
                    MessageHandler messageHandler;
                    Collection<Subscription> subsPerType = null;

                    for (iterator = messageHandlers.iterator(); iterator.hasNext();) {
                        messageHandler = iterator.next();

                        // now add this subscription to each of the handled types
                        // this can safely be called concurrently
                        subsPerType = getSubsPerType(messageHandler, subsPerMessageSingle, subsPerMessageMulti, varArgPossibility);

                        // create the subscription
                        Subscription subscription = new Subscription(messageHandler);
                        subscription.subscribe(listener);

                        subsPerListener.add(subscription); // activates this sub for sub/unsub
                        subsPerType.add(subscription);  // activates this sub for publication
                    }

                    subsPerListener2.put(listenerClass, subsPerListener);
                }
            } else {
                Iterator<Subscription> iterator;
                Subscription sub;

                for (iterator = subsPerListener.iterator(); iterator.hasNext();) {
                    sub = iterator.next();
                    sub.subscribe(listener);
                }
            }
        }
    }

    private final Collection<Subscription> getSubsPerType(MessageHandler messageHandler,
                                                          ConcurrentMap<Class<?>, Collection<Subscription>> subsPerMessageSingle,
                                                          HashMapTree<Class<?>, Collection<Subscription>> subsPerMessageMulti,
                                                          VarArgPossibility varArgPossibility) {

        Class<?>[] types = messageHandler.getHandledMessages();
        int size = types.length;

        ConcurrentSet<Subscription> subsPerType;

        SubscriptionUtils utils = this.utils;
        switch (size) {
            case 1: {
                SubscriptionHolder subHolderConcurrent = this.subHolderConcurrent;
                subsPerType = subHolderConcurrent.get();

                Collection<Subscription> putIfAbsent = subsPerMessageSingle.putIfAbsent(types[0], subsPerType);
                if (putIfAbsent != null) {
                    return putIfAbsent;
                } else {
                    subHolderConcurrent.set(subHolderConcurrent.initialValue());
                    boolean isArray = utils.isArray(types[0]);
                    if (isArray) {
                        varArgPossibility.set(true);
                    }

                    // cache the super classes
                    utils.getSuperClasses(types[0], isArray);

                    return subsPerType;
                }
            }
            case 2: {
                // the HashMapTree uses read/write locks, so it is only accessible one thread at a time
                SubscriptionHolder subHolderSingle = this.subHolderSingle;
                subsPerType = subHolderSingle.get();

                Collection<Subscription> putIfAbsent = subsPerMessageMulti.putIfAbsent(subsPerType, types[0], types[1]);
                if (putIfAbsent != null) {
                    return putIfAbsent;
                } else {
                    subHolderSingle.set(subHolderSingle.initialValue());

                    // cache the super classes
                    utils.getSuperClasses(types[0]);
                    utils.getSuperClasses(types[1]);

                    return subsPerType;
                }
            }
            case 3: {
                // the HashMapTree uses read/write locks, so it is only accessible one thread at a time
                SubscriptionHolder subHolderSingle = this.subHolderSingle;
                subsPerType = subHolderSingle.get();

                Collection<Subscription> putIfAbsent = subsPerMessageMulti.putIfAbsent(subsPerType, types[0], types[1], types[2]);
                if (putIfAbsent != null) {
                    return putIfAbsent;
                } else {
                    subHolderSingle.set(subHolderSingle.initialValue());

                    // cache the super classes
                    utils.getSuperClasses(types[0]);
                    utils.getSuperClasses(types[1]);
                    utils.getSuperClasses(types[2]);

                    return subsPerType;
                }
            }
            default: {
                // the HashMapTree uses read/write locks, so it is only accessible one thread at a time
                SubscriptionHolder subHolderSingle = this.subHolderSingle;
                subsPerType = subHolderSingle.get();

                Collection<Subscription> putIfAbsent = subsPerMessageMulti.putIfAbsent(subsPerType, types);
                if (putIfAbsent != null) {
                    return putIfAbsent;
                } else {
                    subHolderSingle.set(subHolderSingle.initialValue());

                    Class<?> c;
                    int length = types.length;
                    for (int i = 0; i < length; i++) {
                        c = types[i];

                        // cache the super classes
                        utils.getSuperClasses(c);
                    }

                    return subsPerType;
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
            Collection<Subscription> subscriptions = this.subscriptionsPerListener.get(listenerClass);
            if (subscriptions != null) {
                Iterator<Subscription> iterator;
                Subscription sub;

                for (iterator = subscriptions.iterator(); iterator.hasNext();) {
                    sub = iterator.next();

                    sub.unsubscribe(listener);
                }
            }
        }
    }

    private void clearConcurrentCollections() {
        this.utils.clear();
        this.varArgUtils.clear();
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

    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public ConcurrentSet<Subscription> getVarArgSubscriptions(Class<?> messageClass) {
        return this.varArgUtils.getVarArgSubscriptions(messageClass);
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
    public Collection<Subscription> getVarArgSuperSubscriptions(final Class<?> messageClass1, final Class<?> messageClass2, final Class<?> messageClass3) {
        return this.varArgUtils.getVarArgSuperSubscriptions(messageClass1, messageClass2, messageClass3);
    }


    // CAN NOT RETURN NULL
    // ALSO checks to see if the superClass accepts subtypes.
    public final Collection<Subscription> getSuperSubscriptions(Class<?> superType) {
        return this.utils.getSuperSubscriptions(superType);
    }

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
}
