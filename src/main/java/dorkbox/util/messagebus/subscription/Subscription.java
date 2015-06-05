package dorkbox.util.messagebus.subscription;

import com.esotericsoftware.reflectasm.MethodAccess;
import dorkbox.util.messagebus.common.HashMapTree;
import dorkbox.util.messagebus.common.MessageHandler;
import dorkbox.util.messagebus.common.StrongConcurrentSetV8;
import dorkbox.util.messagebus.dispatch.IHandlerInvocation;
import dorkbox.util.messagebus.dispatch.ReflectiveHandlerInvocation;
import dorkbox.util.messagebus.dispatch.SynchronizedHandlerInvocation;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import org.omg.CORBA.BooleanHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A subscription is a thread-safe container that manages exactly one message handler of all registered
 * message listeners of the same class, i.e. all subscribed instances (excluding subclasses) of a SingleMessageHandler.class
 * will be referenced in the subscription created for SingleMessageHandler.class.
 *
 * There will be as many unique subscription objects per message listener class as there are message handlers
 * defined in the message listeners class hierarchy.
 *
 * The subscription provides functionality for message publication by means of delegation to the respective
 * message dispatcher.
 *
 * @author bennidi
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class Subscription {
    private static final AtomicInteger ID_COUNTER = new AtomicInteger();
    public final int ID = ID_COUNTER.getAndIncrement();


    // the handler's metadata -> for each handler in a listener, a unique subscription context is created
    private final MessageHandler handlerMetadata;

    private final IHandlerInvocation invocation;
    private final Collection<Object> listeners;

    public Subscription(MessageHandler handler) {
        this.handlerMetadata = handler;
        this.listeners = new StrongConcurrentSetV8<Object>(16, 0.85F, 15);
//        this.listeners = new StrongConcurrentSet<Object>(16, 0.85F);
//        this.listeners = new ConcurrentLinkedQueue2<Object>();
//        this.listeners = new CopyOnWriteArrayList<Object>();

        IHandlerInvocation invocation = new ReflectiveHandlerInvocation();
        if (handler.isSynchronized()) {
            invocation = new SynchronizedHandlerInvocation(invocation);
        }

        this.invocation = invocation;
    }

    public final MessageHandler getHandlerMetadata() {
        return handlerMetadata;
    }

    public Class<?>[] getHandledMessageTypes() {
        return this.handlerMetadata.getHandledMessages();
    }

    public final boolean acceptsSubtypes() {
        return this.handlerMetadata.acceptsSubtypes();
    }

    public final boolean acceptsVarArgs() {
        return this.handlerMetadata.acceptsVarArgs();
    }

    public final boolean isEmpty() {
        return this.listeners.isEmpty();
    }

    public final void subscribe(Object listener) {
        this.listeners.add(listener);
    }

    /**
     * @return TRUE if the element was removed
     */
    public final boolean unsubscribe(Object existingListener) {
        return this.listeners.remove(existingListener);
    }

    // only used in unit-test
    public int size() {
        return this.listeners.size();
    }

    /**
     * @return true if there were listeners for this publication, false if there was nothing
     */
    public final void publish(final Object message) throws Throwable {
        final MethodAccess handler = this.handlerMetadata.getHandler();
        final int handleIndex = this.handlerMetadata.getMethodIndex();
        final IHandlerInvocation invocation = this.invocation;

        Iterator<Object> iterator;
        Object listener;

        for (iterator = this.listeners.iterator(); iterator.hasNext();) {
            listener = iterator.next();

            invocation.invoke(listener, handler, handleIndex, message);
        }
    }

    /**
     * @return true if there were listeners for this publication, false if there was nothing
     */
    public void publishToSubscription(ErrorHandlingSupport errorHandler, BooleanHolder booleanHolder, Object message1, Object message2) {
//        StrongConcurrentSet<Object> listeners = this.listeners;
//
//        if (!listeners.isEmpty()) {
//            MethodAccess handler = this.handlerMetadata.getHandler();
//            int handleIndex = this.handlerMetadata.getMethodIndex();
//            IHandlerInvocation invocation = this.invocation;
//
//
//            ISetEntry<Object> current = listeners.head;
//            Object listener;
//            while (current != null) {
//                listener = current.getValue();
//                current = current.next();
//
//                try {
//                    invocation.invoke(listener, handler, handleIndex, message1, message2);
//                } catch (IllegalAccessException e) {
//                    errorHandler.handlePublicationError(new PublicationError()
//                                                            .setMessage("Error during invocation of message handler. " +
//                                                                        "The class or method is not accessible")
//                                                            .setCause(e)
//                                                            .setMethodName(handler.getMethodNames()[handleIndex])
//                                                            .setListener(listener)
//                                                            .setPublishedObject(message1, message2));
//                } catch (IllegalArgumentException e) {
//                    errorHandler.handlePublicationError(new PublicationError()
//                                                            .setMessage("Error during invocation of message handler. " +
//                                                                        "Wrong arguments passed to method. Was: " +
//                                                                            message1.getClass() + ", " +
//                                                                            message2.getClass()
//                                                                        + ".  Expected: " + handler.getParameterTypes()[0] + ", " +
//                                                                                            handler.getParameterTypes()[1]
//                                                                            )
//                                                            .setCause(e)
//                                                            .setMethodName(handler.getMethodNames()[handleIndex])
//                                                            .setListener(listener)
//                                                            .setPublishedObject(message1, message2));
//                } catch (Throwable e) {
//                    errorHandler.handlePublicationError(new PublicationError()
//                                                            .setMessage("Error during invocation of message handler. " +
//                                                                        "The Message handler code threw an exception")
//                                                            .setCause(e)
//                                                            .setMethodName(handler.getMethodNames()[handleIndex])
//                                                            .setListener(listener)
//                                                            .setPublishedObject(message1, message2));
//                }
//            }
//            booleanHolder.bool = true;
//        }
    }

    /**
     * @return true if there were listeners for this publication, false if there was nothing
     */
    public void publishToSubscription(ErrorHandlingSupport errorHandler, BooleanHolder booleanHolder, Object message1, Object message2, Object message3) {
//        StrongConcurrentSet<Object> listeners = this.listeners;
//
//        if (!listeners.isEmpty()) {
//            MethodAccess handler = this.handlerMetadata.getHandler();
//            int handleIndex = this.handlerMetadata.getMethodIndex();
//            IHandlerInvocation invocation = this.invocation;
//
//
//            ISetEntry<Object> current = listeners.head;
//            Object listener;
//            while (current != null) {
//                listener = current.getValue();
//                current = current.next();
//
//                try {
//                    invocation.invoke(listener, handler, handleIndex, message1, message2, message3);
//                } catch (IllegalAccessException e) {
//                    errorHandler.handlePublicationError(new PublicationError()
//                                                            .setMessage("Error during invocation of message handler. " +
//                                                                        "The class or method is not accessible")
//                                                            .setCause(e)
//                                                            .setMethodName(handler.getMethodNames()[handleIndex])
//                                                            .setListener(listener)
//                                                            .setPublishedObject(message1, message2, message3));
//                } catch (IllegalArgumentException e) {
//                    errorHandler.handlePublicationError(new PublicationError()
//                                                            .setMessage("Error during invocation of message handler. " +
//                                                                        "Wrong arguments passed to method. Was: " +
//                                                            message1.getClass() + ", " +
//                                                            message2.getClass() + ", " +
//                                                            message3.getClass()
//                                                            + ".  Expected: " + handler.getParameterTypes()[0] + ", " +
//                                                                                handler.getParameterTypes()[1] + ", " +
//                                                                                handler.getParameterTypes()[2]
//                                                            )
//                                                            .setCause(e)
//                                                            .setMethodName(handler.getMethodNames()[handleIndex])
//                                                            .setListener(listener)
//                                                            .setPublishedObject(message1, message2, message3));
//                } catch (Throwable e) {
//                    errorHandler.handlePublicationError(new PublicationError()
//                                                            .setMessage("Error during invocation of message handler. " +
//                                                                        "The Message handler code threw an exception")
//                                                            .setCause(e)
//                                                            .setMethodName(handler.getMethodNames()[handleIndex])
//                                                            .setListener(listener)
//                                                            .setPublishedObject(message1, message2, message3));
//                }
//            }
//            booleanHolder.bool = true;
//        }
    }


    @Override
    public int hashCode() {
        return this.ID;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        Subscription other = (Subscription) obj;
        return this.ID == other.ID;
    }

    // inside a write lock
    // also puts it into the correct map if it's not already there
    public Collection<Subscription> createPublicationSubscriptions(final Map<Class<?>, ArrayList<Subscription>> subsPerMessageSingle,
                                                                   final HashMapTree<Class<?>, ArrayList<Subscription>> subsPerMessageMulti,
                                                                   AtomicBoolean varArgPossibility, SubscriptionUtils utils) {

        final Class<?>[] messageHandlerTypes = handlerMetadata.getHandledMessages();
        final int size = messageHandlerTypes.length;

//        ConcurrentSet<Subscription> subsPerType;

//        SubscriptionUtils utils = this.utils;
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
                    utils.cacheSuperClasses(type0);

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
}
