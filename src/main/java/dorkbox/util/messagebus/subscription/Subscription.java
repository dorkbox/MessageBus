package dorkbox.util.messagebus.subscription;

import java.lang.reflect.InvocationTargetException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

import com.esotericsoftware.reflectasm.MethodAccess;

import dorkbox.util.messagebus.common.thread.BooleanHolder;
import dorkbox.util.messagebus.common.thread.ConcurrentSet;
import dorkbox.util.messagebus.dispatch.IHandlerInvocation;
import dorkbox.util.messagebus.dispatch.ReflectiveHandlerInvocation;
import dorkbox.util.messagebus.dispatch.SynchronizedHandlerInvocation;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.error.PublicationError;
import dorkbox.util.messagebus.listener.MessageHandler;

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
    private static AtomicInteger ID_COUNTER = new AtomicInteger();
    private final int ID = ID_COUNTER.getAndIncrement();


    // the handler's metadata -> for each handler in a listener, a unique subscription context is created
    private final MessageHandler handlerMetadata;

    private final IHandlerInvocation invocation;
    private final ConcurrentSet<Object> listeners;

    public Subscription(MessageHandler handler) {
        this.handlerMetadata = handler;
        this.listeners = new ConcurrentSet<Object>();

        IHandlerInvocation invocation = new ReflectiveHandlerInvocation();
        if (handler.isSynchronized()) {
            invocation = new SynchronizedHandlerInvocation(invocation);
        }

        this.invocation = invocation;
    }

    public Class<?>[] getHandledMessageTypes() {
        return this.handlerMetadata.getHandledMessages();
    }

    public boolean acceptsSubtypes() {
        return this.handlerMetadata.acceptsSubtypes();
    }

    public boolean acceptsVarArgs() {
        return this.handlerMetadata.acceptsVarArgs();
    }

    public boolean isEmpty() {
        return this.listeners.isEmpty();
    }

    public void subscribe(Object listener) {
        this.listeners.add(listener);
    }

    /**
     * @return TRUE if the element was removed
     */
    public boolean unsubscribe(Object existingListener) {
        return this.listeners.remove(existingListener);
    }

    /**
     * Check whether this subscription manages a message handler of the given message listener class
     */
    // only in unit test
    public boolean belongsTo(Class<?> listener){
        return this.handlerMetadata.isFromListener(listener);
    }

    // only used in unit-test
    public int size() {
        return this.listeners.size();
    }

    /**
     * @return true if there were listeners for this publication, false if there was nothing
     */
    public void publishToSubscription(ErrorHandlingSupport errorHandler, BooleanHolder booleanHolder, Object message) {
        ConcurrentSet<Object> listeners = this.listeners;

        if (!listeners.isEmpty()) {
            MethodAccess handler = this.handlerMetadata.getHandler();
            int handleIndex = this.handlerMetadata.getMethodIndex();
            IHandlerInvocation invocation = this.invocation;

            Iterator<Object> iterator;
            Object listener;

            for (iterator = listeners.iterator(); iterator.hasNext();) {
                listener = iterator.next();

                try {
                    invocation.invoke(listener, handler, handleIndex, message);
                } catch (IllegalAccessException e) {
                    errorHandler.handlePublicationError(new PublicationError()
                                    .setMessage("Error during invocation of message handler. " +
                                                "The class or method is not accessible")
                                    .setCause(e)
                                    .setMethodName(handler.getMethodNames()[handleIndex])
                                    .setListener(listener)
                                    .setPublishedObject(message));
                } catch (IllegalArgumentException e) {
                    errorHandler.handlePublicationError(new PublicationError()
                                    .setMessage("Error during invocation of message handler. " +
                                                "Wrong arguments passed to method. Was: " + message.getClass()
                                                + "Expected: " + handler.getParameterTypes()[0])
                                    .setCause(e)
                                    .setMethodName(handler.getMethodNames()[handleIndex])
                                    .setListener(listener)
                                    .setPublishedObject(message));
                } catch (InvocationTargetException e) {
                    errorHandler.handlePublicationError(new PublicationError()
                                    .setMessage("Error during invocation of message handler. " +
                                                "Message handler threw exception")
                                    .setCause(e)
                                    .setMethodName(handler.getMethodNames()[handleIndex])
                                    .setListener(listener)
                                    .setPublishedObject(message));
                } catch (Throwable e) {
                    errorHandler.handlePublicationError(new PublicationError()
                                    .setMessage("Error during invocation of message handler. " +
                                                "The handler code threw an exception")
                                    .setCause(e)
                                    .setMethodName(handler.getMethodNames()[handleIndex])
                                    .setListener(listener)
                                    .setPublishedObject(message));
                }
            }
            booleanHolder.bool = true;
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
//                } catch (InvocationTargetException e) {
//                    errorHandler.handlePublicationError(new PublicationError()
//                                                            .setMessage("Error during invocation of message handler. " +
//                                                                        "Message handler threw exception")
//                                                            .setCause(e)
//                                                            .setMethodName(handler.getMethodNames()[handleIndex])
//                                                            .setListener(listener)
//                                                            .setPublishedObject(message1, message2));
//                } catch (Throwable e) {
//                    errorHandler.handlePublicationError(new PublicationError()
//                                                            .setMessage("Error during invocation of message handler. " +
//                                                                        "The handler code threw an exception")
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
//                } catch (InvocationTargetException e) {
//                    errorHandler.handlePublicationError(new PublicationError()
//                                                            .setMessage("Error during invocation of message handler. " +
//                                                                        "Message handler threw exception")
//                                                            .setCause(e)
//                                                            .setMethodName(handler.getMethodNames()[handleIndex])
//                                                            .setListener(listener)
//                                                            .setPublishedObject(message1, message2, message3));
//                } catch (Throwable e) {
//                    errorHandler.handlePublicationError(new PublicationError()
//                                                            .setMessage("Error during invocation of message handler. " +
//                                                                        "The handler code threw an exception")
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
}
