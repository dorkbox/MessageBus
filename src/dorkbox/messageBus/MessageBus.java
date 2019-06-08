/*
 * Copyright 2015 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.messageBus;

import java.util.concurrent.BlockingQueue;

import com.conversantmedia.util.concurrent.DisruptorBlockingQueue;
import com.conversantmedia.util.concurrent.SpinPolicy;

import dorkbox.messageBus.dispatch.Dispatch;
import dorkbox.messageBus.dispatch.DispatchCancel;
import dorkbox.messageBus.dispatch.DispatchExact;
import dorkbox.messageBus.dispatch.DispatchExactWithSuperTypes;
import dorkbox.messageBus.error.ErrorHandler;
import dorkbox.messageBus.error.IPublicationErrorHandler;
import dorkbox.messageBus.subscription.SubscriptionManager;
import dorkbox.messageBus.synchrony.Async;
import dorkbox.messageBus.synchrony.MessageHolder;
import dorkbox.messageBus.synchrony.Sync;
import dorkbox.messageBus.synchrony.Synchrony;

/**
 * A message bus offers facilities for publishing messages to the message handlers of registered listeners.
 * <p/>
 *
 * Because the message bus keeps track of classes that are subscribed and published, reloading the classloader means that you will need to
 * SHUTDOWN the messagebus when you unload the classloader, and then re-subscribe relevant classes when you reload the classes.
 * <p/>
 *
 * Messages can be published synchronously or asynchronously and may be of any type that is a valid sub type of the type parameter T.
 * Message handlers can be invoked synchronously or asynchronously depending on their configuration. Thus, there
 * are two notions of synchronicity / asynchronicity. One on the caller side, e.g. the invocation of the message publishing
 * methods. The second on the handler side, e.g. whether the handler is invoked in the same or a different thread.
 *
 * <p/>
 * Each message publication is isolated from all other running publications such that it does not interfere with them.
 * Hence, the bus generally expects message handlers to be stateless as it may invoke them concurrently if multiple
 * messages publish published asynchronously. If handlers are stateful and not thread-safe they can be marked to be invoked
 * in a synchronized fashion using @Synchronized annotation
 *
 * <p/>
 * A listener is any object that defines at least one message handler and that has been subscribed to at least
 * one message bus. A message handler can be any method that accepts exactly one parameter (the message) and is marked
 * as a message handler using the @Handler annotation.
 *
 * <p/>
 * By default, the bus uses weak references to all listeners such that registered listeners do not need to
 * be explicitly unregistered to be eligible for garbage collection. Dead (garbage collected) listeners are
 * removed on-the-fly as messages publish dispatched. This can be changed using the @Listener annotation.
 *
 * <p/>
 * Generally message handlers will be invoked in inverse sequence of subscription but any
 * client using this bus should not rely on this assumption. The basic contract of the bus is that it will deliver
 * a specific message exactly once to each of the respective message handlers.
 *
 * <p/>
 * Messages are dispatched to all listeners that accept the type or supertype of the dispatched message.
 *
 * <p/>
 * You may cancel any further dispatch of a message via {@link #cancel()}
 *
 * <p/>
 * Subscribed message handlers are available to all pending message publications that have not yet started processing.
 * Any message listener may only be subscribed once -> subsequent subscriptions of an already subscribed message listener
 * will be silently ignored)
 *
 * <p/>
 * Removing a listener (unsubscribing) means removing all subscribed message handlers of that listener. This remove operation
 * immediately takes effect and on all running dispatch processes -> A removed listener (a listener
 * is considered removed after the remove(Object) call returned) will under no circumstances receive any message publications.
 * Any running message publication that has not yet delivered the message to the removed listener will not see the listener
 * after the remove operation completed.
 *
 * <p/>
 * NOTE: Generic type parameters of messages will not be taken into account, e.g. a List<Long> will
 * publish dispatched to all message handlers that take an instance of List as their parameter
 *
 *
 *
 * See this post for insight on how it operates:  http://psy-lob-saw.blogspot.com/2012/12/atomiclazyset-is-performance-win-for.html
 * TLDR: we use single-writer-principle + lazySet/get for major performance
 *
 * @author bennidi
 *         Date: 2/8/12
 * @author dorkbox, llc
 *         Date: 2/2/15
 */

@SuppressWarnings("WeakerAccess")
public
class MessageBus {
    /**
     * Gets the version number.
     */
    public static
    String getVersion() {
        return "1.20";
    }

    /**
     * Cancels the publication of the message or messages. Only applicable for the current dispatched message.
     * <p>
     * No more subscribers for this message will be called.
     */
    public static
    void cancel() {
        throw new DispatchCancel();
    }

    /**
     * Helper method to determine the dispatch mode
     */
    private static
    Dispatch getDispatch(final DispatchMode dispatchMode, final ErrorHandler errorHandler, final SubscriptionManager subscriptionManager) {
        if (dispatchMode == DispatchMode.Exact) {
            return new DispatchExact(errorHandler, subscriptionManager);
        }

        return new DispatchExactWithSuperTypes(errorHandler, subscriptionManager);
    }

    private final ErrorHandler errorHandler;

    private final SubscriptionManager subscriptionManager;

    private final Synchrony syncPublication;
    private final Synchrony asyncPublication;

    /**
     * Will permit subType matching for matching what subscription handles which message
     * <p>
     * Will use half of CPUs available for dispatching async messages
     * <p>
     * Will use the Conversant Disruptor as the blocking queue implementation for asynchronous message publication
     * <p>
     * Will use half of CPUs available for dispatching async messages
     */
    public
    MessageBus() {
        this(Runtime.getRuntime().availableProcessors()/2);
    }


    /**
     * Will permit subType matching for matching what subscription handles which message
     * <p>
     * Will use half of CPUs available for dispatching async messages
     * <p>
     * Will use the Conversant Disruptor as the blocking queue implementation for asynchronous message publication
     *
     * @param numberOfThreads how many threads to use for dispatching async messages
     */
    public
    MessageBus(final int numberOfThreads) {
        this(DispatchMode.ExactWithSuperTypes, SubscriptionMode.StrongReferences, numberOfThreads);
    }


    /**
     * Will use half of CPUs available for dispatching async messages
     * <p>
     * Will use the Conversant Disruptor as the blocking queue implementation for asynchronous publication
     *
     * @param dispatchMode     Specifies which Dispatch Mode (Exact or ExactWithSuperTypes) to allow what subscription hierarchies receive the publication of a message.
     * @param subscriptionMode Specifies which Subscription Mode Mode (Strong or Weak) to change how subscription handlers are saved internally.
     */
    public
    MessageBus(final DispatchMode dispatchMode, final SubscriptionMode subscriptionMode) {
        this(dispatchMode, subscriptionMode, Runtime.getRuntime().availableProcessors()/2);
    }


    /**
     * Will use the Conversant Disruptor as the blocking queue implementation for asynchronous publication
     *
     * @param dispatchMode     Specifies which Dispatch Mode (Exact or ExactWithSuperTypes) to allow what subscription hierarchies receive the publication of a message.
     * @param subscriptionMode Specifies which Subscription Mode Mode (Strong or Weak) to change how subscription handlers are saved internally.
     * @param numberOfThreads  how many threads to use for dispatching async messages
     */
    public
    MessageBus(final DispatchMode dispatchMode, final SubscriptionMode subscriptionMode, final int numberOfThreads) {
        this(dispatchMode, subscriptionMode, new DisruptorBlockingQueue<MessageHolder>(1024, SpinPolicy.BLOCKING), numberOfThreads);
    }


    /**
     * Will use the Conversant Disruptor for asynchronous dispatch of published messages.
     * <p>
     * The benefit of such is that it is VERY high performance and generates zero garbage on the heap.
     *
     * @param dispatchMode     Specifies which Dispatch Mode (Exact or ExactWithSuperTypes) to allow what subscription hierarchies receive the publication of a message.
     * @param subscriptionMode Specifies which Subscription Mode Mode (Strong or Weak) to change how subscription handlers are saved internally.
     * @param dispatchQueue    Specified Blocking queue implementation for managing asynchronous message publication
     * @param numberOfThreads  how many threads to use for dispatching async messages
     */
    public
    MessageBus(final DispatchMode dispatchMode, final SubscriptionMode subscriptionMode, final BlockingQueue<MessageHolder> dispatchQueue, final int numberOfThreads) {
        this.errorHandler = new ErrorHandler();

        // Will subscribe and publish using all provided parameters in the method signature (for subscribe), and arguments (for publish)
        this.subscriptionManager = new SubscriptionManager(subscriptionMode);

        Dispatch dispatch = getDispatch(dispatchMode, errorHandler, subscriptionManager);

        syncPublication = new Sync(dispatch);
        asyncPublication = new Async(numberOfThreads, dispatch, dispatchQueue, errorHandler);
    }


    /**
     * Subscribe all handlers of the given listener. Any listener is only subscribed once and
     * subsequent subscriptions of an already subscribed listener will be silently ignored
     */
    public
    void subscribe(final Object listener) {
        if (listener == null) {
            return;
        }

        // single writer principle using synchronised
        subscriptionManager.subscribe(listener);
    }


    /**
     * Immediately remove all registered message handlers (if any) of the given listener.
     * <p>
     * When this call returns all handlers have effectively been removed and will not
     * receive any messages (provided that running publications/iterators in other threads
     * have not yet obtained a reference to the listener)
     * <p>
     * A call to this method passing any object that is not subscribed will not have any effect and is silently ignored.
     */
    public
    void unsubscribe(final Object listener) {
        if (listener == null) {
            return;
        }

        // single writer principle using synchronised
        subscriptionManager.unsubscribe(listener);
    }


    /**
     * Synchronously publish a message to all registered listeners.
     * <p>
     * This includes listeners defined for super types of the given message type, provided they are not configured
     * to reject valid subtypes.
     * <p>
     * The call returns when all matching subscription handlers of all registered listeners have been notified (invoked) of the message.
     */
    public
    void publish(final Object message) {
        syncPublication.publish(message);
    }


    /**
     * Synchronously publish <b>TWO</b> messages to all registered listeners (that match the signature).
     * <p>
     * This includes listeners defined for super types of the given message type, provided they are not configured
     * to reject valid subtypes.
     * <p>
     * The call returns when all matching subscription handlers of all registered listeners have been notified (invoked) of the message.
     */
    public
    void publish(final Object message1, final Object message2) {
        syncPublication.publish(message1, message2);
    }


    /**
     * Synchronously publish <b>THREE</b> messages to all registered listeners (that match the signature).
     * <p>
     * This includes listeners defined for super types of the given message type, provided they are not configured
     * to reject valid subtypes.
     * <p>
     * The call returns when all matching subscription handlers of all registered listeners have been notified (invoked) of the message.
     */
    public
    void publish(final Object message1, final Object message2, final Object message3) {
        syncPublication.publish(message1, message2, message3);
    }


    /**
     * <i>Asynchronously</i> publish the message to all registered listeners (that match the signature).
     * <p>
     * This includes listeners defined for super types of the given message type, provided they are not configured to reject
     * valid subtypes.
     * <p>
     * This call returns immediately.
     */
    public
    void publishAsync(final Object message) {
        asyncPublication.publish(message);
    }


    /**
     * <i>Asynchronously</i> publish <b>TWO</b> messages to all registered listeners (that match the signature).
     * <p>
     * This
     * includes listeners defined for super types of the given message type, provided they are not configured
     * to reject valid subtypes.
     * <p>
     * This call returns immediately.
     */
    public
    void publishAsync(final Object message1, final Object message2) {
        asyncPublication.publish(message1, message2);
    }


    /**
     * <i>Asynchronously</i> publish <b>THREE</b> messages to all registered listeners (that match the signature).
     * <p>
     * This includes listeners defined for super types of the given message type, provided they are not configured to
     * reject valid subtypes.
     * <p>
     * This call returns immediately.
     */
    public
    void publishAsync(final Object message1, final Object message2, final Object message3) {
        asyncPublication.publish(message1, message2, message3);
    }


    /**
     * Publication errors may occur at various points of time during message delivery. A handler may throw an exception,
     * may not be accessible due to security constraints or is not annotated properly.
     * <p>
     * In any of all possible cases a publication error is created and passed to each of the registered error handlers.
     * A call to this method will add the given error handler to the chain
     */
    public
    void addErrorHandler(final IPublicationErrorHandler errorHandler) {
        this.errorHandler.addErrorHandler(errorHandler);
    }


    /**
     * Check whether any asynchronous message publications are pending to be processed.
     * <p>
     * Because of the nature of MULTI-THREADED, ASYNCHRONOUS environments, it is ** MORE THAN LIKELY **  this will not be an accurate reflection of the current state.
     *
     * @return true if there are still message publications waiting to be processed.
     */
    public final
    boolean hasPendingMessages() {
        return asyncPublication.hasPendingMessages();
    }


    /**
     * Shutdown the bus such that it will stop delivering asynchronous messages. Executor service and
     * other internally used threads will be shutdown gracefully.
     * <p>
     * After calling shutdown it is not safe to further use the message bus.
     */
    public
    void shutdown() {
        this.syncPublication.shutdown();
        this.asyncPublication.shutdown();
        this.subscriptionManager.shutdown();
    }
}

