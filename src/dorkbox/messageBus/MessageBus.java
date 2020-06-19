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

import dorkbox.messageBus.dispatch.Dispatch;
import dorkbox.messageBus.dispatch.DispatchExact;
import dorkbox.messageBus.dispatch.DispatchExactWithSuperTypes;
import dorkbox.messageBus.error.ErrorHandler;
import dorkbox.messageBus.error.IPublicationErrorHandler;
import dorkbox.messageBus.publication.ConversantDisruptor;
import dorkbox.messageBus.publication.DirectInvocation;
import dorkbox.messageBus.publication.LmaxDisruptor;
import dorkbox.messageBus.publication.Publisher;
import dorkbox.messageBus.subscription.SubscriptionManager;

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
        return "2.3";
    }

    /**
     * Always return at least 1 thread
     */
    private static
    int getMinNumberOfThreads(final int numberOfThreads) {
        if (numberOfThreads < 1) {
            return 1;
        }
        return numberOfThreads;
    }

    private final Dispatch dispatch;
    private final ErrorHandler errorHandler = new ErrorHandler();
    private final DispatchMode dispatchMode;

    private final SubscriptionManager subscriptionManager;
    private final AsyncPublicationMode publicationMode;
    private final SubscriptionMode subscriptionMode;
    private final int numberOfThreads;

    private final Publisher syncPublisher;
    private final Publisher asyncPublisher;

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
     * Will use the LMAX Disruptor as the blocking queue implementation for asynchronous publication
     *
     * @param dispatchMode     Specifies which Dispatch Mode (Exact or ExactWithSuperTypes) to allow what subscription hierarchies receive the publication of a message.
     * @param subscriptionMode Specifies which Subscription Mode Mode (Strong or Weak) to change how subscription handlers are saved internally.
     */
    public
    MessageBus(final DispatchMode dispatchMode, final SubscriptionMode subscriptionMode) {
        this(dispatchMode, subscriptionMode, Runtime.getRuntime().availableProcessors()/2);
    }


    /**
     * Will use the LMAX Disruptor as the blocking queue implementation for asynchronous publication
     *
     * @param dispatchMode     Specifies which Dispatch Mode (Exact or ExactWithSuperTypes) to allow what subscription hierarchies receive the publication of a message.
     * @param subscriptionMode Specifies which Subscription Mode Mode (Strong or Weak) to change how subscription handlers are saved internally.
     * @param numberOfThreads  how many threads to use for dispatching async messages
     */
    public
    MessageBus(final DispatchMode dispatchMode, final SubscriptionMode subscriptionMode, final int numberOfThreads) {
        this(dispatchMode, subscriptionMode, AsyncPublicationMode.LmaxDisruptor, numberOfThreads);
    }


    /**
     * @param dispatchMode     Specifies which Dispatch Mode (Exact or ExactWithSuperTypes) to allow what subscription hierarchies receive the publication of a message.
     * @param subscriptionMode Specifies which Subscription Mode (Strong or Weak) to change how subscription handlers are saved internally.
     * @param publicationMode  Specifies which Publication Mode (LMAX or Conversant disruptors) for executing messages asynchronously.
     * @param numberOfThreads  how many threads to use for dispatching async messages
     */
    public
    MessageBus(final DispatchMode dispatchMode, final SubscriptionMode subscriptionMode, final AsyncPublicationMode publicationMode, final int numberOfThreads) {
        this.dispatchMode = dispatchMode;
        this.subscriptionMode = subscriptionMode;
        this.publicationMode = publicationMode;

        // make sure there are ALWAYS at least 1 thread, even if (by accident) 0 threads were specified because of rounding errors.
        int minNumberOfThreads = getMinNumberOfThreads(numberOfThreads);
        this.numberOfThreads = minNumberOfThreads;

        // Will subscribe and publish using all provided parameters in the method signature (for subscribe), and arguments (for publish)
        this.subscriptionManager = new SubscriptionManager(subscriptionMode);

        if (dispatchMode == DispatchMode.Exact) {
            this.dispatch =  new DispatchExact();
        } else {
            this.dispatch = new DispatchExactWithSuperTypes();
        }

        syncPublisher = new DirectInvocation();



        if (publicationMode == AsyncPublicationMode.LmaxDisruptor) {
            asyncPublisher = new LmaxDisruptor(minNumberOfThreads, errorHandler);
        } else {
            asyncPublisher = new ConversantDisruptor(minNumberOfThreads);
        }
    }

    /**
     * Creates a copy of this messagebus - BUT - instead of making a copy of everything, it shares the async thread executor.
     * </p>
     * This will permit unique subscriptions.
     * </p>
     * Only the original messagebus can shutdown the thread executor
     */
    private
    MessageBus(final MessageBus messageBus) {
        this.dispatchMode = messageBus.dispatchMode;
        this.subscriptionMode = messageBus.subscriptionMode;
        this.publicationMode = messageBus.publicationMode;
        this.numberOfThreads = messageBus.numberOfThreads;


        // Will subscribe and publish using all provided parameters in the method signature (for subscribe), and arguments (for publish)
        this.subscriptionManager = new SubscriptionManager(subscriptionMode);

        this.dispatch = messageBus.dispatch;
        this.syncPublisher = messageBus.syncPublisher;


        // we have to make sure that calling .shutdown() DOES NOT shutdown the thread executor for these!
        if (publicationMode == AsyncPublicationMode.LmaxDisruptor) {
            asyncPublisher = new LmaxDisruptor((LmaxDisruptor) messageBus.asyncPublisher) {
                @Override
                public
                void shutdown() {
                    // do nothing for a clone!
                }
            };

        } else {
            asyncPublisher = new ConversantDisruptor((ConversantDisruptor) messageBus.asyncPublisher) {
                @Override
                public
                void shutdown() {
                    // do nothing for a clone!
                }
            };
        }
    }

    /**
     * Clones the this MessageBus, sharing the same configurations and executor for Async Publications.
     * </p>
     * This will permit unique subscriptions.
     * </p>
     * Calling {@link #shutdown()} on this new MessageBus will NOT shutdown the thread executor. Only the ORIGINAL
     * MessageBus can shut it down.
     */
    public
    MessageBus cloneWithSharedExecutor() {
        return new MessageBus(this);
    }

    /**
     * Subscribe all handlers of the given listener. Any listener is only subscribed once and
     * subsequent subscriptions of an already subscribed listener will be silently ignored
     */
    public
    void subscribe(final Object listener) {
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
        dispatch.publish(syncPublisher, errorHandler, subscriptionManager, message);
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
        dispatch.publish(syncPublisher, errorHandler, subscriptionManager, message1, message2);
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
        dispatch.publish(syncPublisher, errorHandler, subscriptionManager, message1, message2, message3);
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
        dispatch.publish(asyncPublisher, errorHandler, subscriptionManager, message);
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
        dispatch.publish(asyncPublisher, errorHandler, subscriptionManager, message1, message2);
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
        dispatch.publish(asyncPublisher, errorHandler, subscriptionManager, message1, message2, message3);
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
        return asyncPublisher.hasPendingMessages();
    }


    /**
     * Shutdown the bus such that it will stop delivering asynchronous messages. Executor service and
     * other internally used threads will be shutdown gracefully.
     * <p>
     * After calling shutdown it is not safe to further use the message bus.
     */
    public
    void shutdown() {
        this.subscriptionManager.shutdown();
        this.asyncPublisher.shutdown();
    }
}

