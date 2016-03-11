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
package dorkbox.messagebus;

import dorkbox.messagebus.error.ErrorHandler;
import dorkbox.messagebus.error.IPublicationErrorHandler;
import dorkbox.messagebus.dispatch.Dispatch;
import dorkbox.messagebus.dispatch.DispatchExact;
import dorkbox.messagebus.dispatch.DispatchExactWithSuperTypes;
import dorkbox.messagebus.subscription.SubscriptionManager;
import dorkbox.messagebus.synchrony.AsyncABQ;
import dorkbox.messagebus.synchrony.AsyncABQ_noGc;
import dorkbox.messagebus.synchrony.AsyncDisruptor;
import dorkbox.messagebus.synchrony.Sync;
import dorkbox.messagebus.synchrony.Synchrony;

/**
 * The base class for all message bus implementations with support for asynchronous message dispatch.
 *
 * See this post for insight on how it operates:  http://psy-lob-saw.blogspot.com/2012/12/atomiclazyset-is-performance-win-for.html
 * TLDR: we use single-writer-principle + lazySet/get for major performance
 *
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public
class MessageBus implements IMessageBus {

    /**
     * By default, we use ASM for accessing methods during the dispatch of messages. This is only available on certain platforms, and so
     * it will gracefully 'fallback' to using standard java reflection to access the methods. "Standard java reflection" is not as fast
     * as ASM, but only marginally.
     *
     * If you would like to use java reflection for accessing methods, set this value to false.
     */
    public static boolean useAsmForDispatch = true;

    /**
     * 'useDisruptorForAsyncPublish' specifies to use the LMAX Disruptor for asynchronous dispatch of published messages. The benefit of
     * such is that it is VERY high performance and generates zero garbage on the heap. The alternative (if this value is false), is to
     * use an ArrayBlockingQueue, which has a "non-garbage" version (which is zero garbage, but slow-ish) and it's opposite (which
     * generates garbage on the heap, but is faster).
     *
     * The disruptor is faster and better than either of these two, however because of it's use of unsafe, it is not available in all
     * circumstances.
     */
    public static boolean useDisruptorForAsyncPublish = true;

    /**
     * When using the ArrayBlockingQueue for the asynchronous dispatch of published messages, there are two modes of operation. A
     * "non-garbage" version (which is zero garbage, but slow-ish) and it's opposite (which generates garbage on the heap, but is faster).
     *
     * By default, we strive to prevent garbage on the heap, so we use the "non-garbage" version. If you don't care about generating
     * garbage on the heap, set this value to false.
     */
    public static boolean useZeroGarbageVersionOfABQ = true;

    /**
     * By default, we use strong references when saving the subscribed listeners (these are the classes & methods that receive messages),
     * however in certain environments (ie: spring), it is desirable to use weak references -- so that there are no memory leaks during
     * the container lifecycle (or, more specifically, so one doesn't have to manually manage the memory).
     *
     * Using weak references is a tad slower than using strong references, since there are additional steps taken when there are orphaned
     * references (when GC occurs) that have to be cleaned up. This cleanup occurs during message publication
     */
    public static boolean useStrongReferencesByDefault = true;


    static {
        // check to see if we can use ASM for method access (it's a LOT faster than reflection). By default, we use ASM.
        if (useAsmForDispatch) {
            // only bother checking if we are different that the defaults
            try {
                Class.forName("com.esotericsoftware.reflectasm.MethodAccess");
            } catch (Exception e) {
                useAsmForDispatch = false;
            }
        }

        // check to see if we can use the disruptor for publication (otherwise, we use native java). The disruptor is a lot faster, but
        // not available on all platforms/JRE's because of it's use of UNSAFE.
        if (useDisruptorForAsyncPublish) {
            // only bother checking if we are different that the defaults
            try {
                Class.forName("com.lmax.disruptor.RingBuffer");
            } catch (Exception e) {
                useDisruptorForAsyncPublish = false;
            }
        }
    }

    /**
     * Gets the version number.
     */
    public static
    String getVersion() {
        return "1.12";
    }

    private final ErrorHandler errorHandler;

    private final SubscriptionManager subscriptionManager;

    private final Dispatch dispatch;
    private final Synchrony syncPublication;
    private final Synchrony asyncPublication;

    /**
     * By default, will permit subType matching, and will use half of CPUs available for dispatching async messages
     */
    public
    MessageBus() {
        this(Runtime.getRuntime().availableProcessors()/2);
    }

    /**
     * By default, will permit subType matching
     *
     * @param numberOfThreads how many threads to use for dispatching async messages
     */
    public
    MessageBus(final int numberOfThreads) {
        this(DispatchMode.ExactWithSuperTypes, numberOfThreads);
    }

    /**
     * By default, will use half of CPUs available for dispatching async messages
     *
     * @param dispatchMode Specifies which publishMode to operate the publication of messages.
     */
    public
    MessageBus(final DispatchMode dispatchMode) {
        this(dispatchMode, Runtime.getRuntime().availableProcessors());
    }

    /**
     * @param dispatchMode     Specifies which publishMode to operate the publication of messages.
     * @param numberOfThreads how many threads to use for dispatching async messages
     */
    public
    MessageBus(final DispatchMode dispatchMode, int numberOfThreads) {
        // round to the nearest power of 2
        numberOfThreads = 1 << (32 - Integer.numberOfLeadingZeros(getMinNumberOfThreads(numberOfThreads) - 1));

        this.errorHandler = new ErrorHandler();

        /**
         * Will subscribe and publish using all provided parameters in the method signature (for subscribe), and arguments (for publish)
         */
        this.subscriptionManager = new SubscriptionManager(useStrongReferencesByDefault);

        switch (dispatchMode) {
            case Exact:
                dispatch = new DispatchExact(errorHandler, subscriptionManager);
                break;

            case ExactWithSuperTypes:
            default:
                dispatch = new DispatchExactWithSuperTypes(errorHandler, subscriptionManager);
                break;
        }

        syncPublication = new Sync();

        // the disruptor is preferred, but if it cannot be loaded -- we want to try to continue working, hence the use of ArrayBlockingQueue
        if (useDisruptorForAsyncPublish) {
            asyncPublication = new AsyncDisruptor(numberOfThreads, errorHandler);
        } else {
            if (useZeroGarbageVersionOfABQ) {
                // no garbage is created, but this is slow (but faster than other messagebus implementations)
                asyncPublication = new AsyncABQ_noGc(numberOfThreads, errorHandler);
            }
            else {
                // garbage is created, but this is fast
                asyncPublication = new AsyncABQ(numberOfThreads, errorHandler);
            }
        }
    }

    /**
     * Always return at least 2 threads
     */
    private static
    int getMinNumberOfThreads(final int numberOfThreads) {
        if (numberOfThreads < 2) {
            return 2;
        }
        return numberOfThreads;
    }


    /**
     * Subscribe all handlers of the given listener. Any listener is only subscribed once and
     * subsequent subscriptions of an already subscribed listener will be silently ignored
     */
    @Override
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
     *
     * When this call returns all handlers have effectively been removed and will not
     * receive any messages (provided that running publications/iterators in other threads
     * have not yet obtained a reference to the listener)
     * <p>
     * A call to this method passing any object that is not subscribed will not have any effect and is silently ignored.
     */
    @Override
    public
    void unsubscribe(final Object listener) {
        if (listener == null) {
            return;
        }

        // single writer principle using synchronised
        subscriptionManager.unsubscribe(listener);
    }


    /**
     * Synchronously publish a message to all registered listeners. This includes listeners
     * defined for super types of the given message type, provided they are not configured
     * to reject valid subtypes. The call returns when all matching handlers of all registered
     * listeners have been notified (invoked) of the message.
     */
    @Override
    public
    void publish(final Object message1) {
        syncPublication.publish(dispatch, message1);
    }


    /**
     * Synchronously publish <b>TWO</b> messages to all registered listeners (that match the signature). This
     * includes listeners defined for super types of the given message type, provided they are not configured
     * to reject valid subtypes. The call returns when all matching handlers of all registered listeners have
     * been notified (invoked) of the message.
     */
    @Override
    public
    void publish(final Object message1, final Object message2) {
        syncPublication.publish(dispatch, message1, message2);
    }


    /**
     * Synchronously publish <b>THREE</b> messages to all registered listeners (that match the signature). This
     * includes listeners defined for super types of the given message type, provided they are not configured
     * to reject valid subtypes. The call returns when all matching handlers of all registered listeners have
     * been notified (invoked) of the message.
     */
    @Override
    public
    void publish(final Object message1, final Object message2, final Object message3) {
        syncPublication.publish(dispatch, message1, message2, message3);
    }


    /**
     * Publish the message asynchronously to all registered listeners (that match the signature). This includes
     * listeners defined for super types of the given message type, provided they are not configured to reject
     * valid subtypes. This call returns immediately.
     */
    @Override
    public
    void publishAsync(final Object message) {
        asyncPublication.publish(dispatch, message);
    }


    /**
     * Publish <b>TWO</b> messages asynchronously to all registered listeners (that match the signature). This
     * includes listeners defined for super types of the given message type, provided they are not configured
     * to reject valid subtypes. This call returns immediately.
     */
    @Override
    public
    void publishAsync(final Object message1, final Object message2) {
        asyncPublication.publish(dispatch, message1, message2);
    }


    /**
     * Publish <b>THREE</b> messages asynchronously to all registered listeners (that match the signature). This
     * includes listeners defined for super types of the given message type, provided they are not configured to
     * reject valid subtypes. This call returns immediately.
     */
    @Override
    public
    void publishAsync(final Object message1, final Object message2, final Object message3) {
        asyncPublication.publish(dispatch, message1, message2, message3);
    }


    /**
     * Publication errors may occur at various points of time during message delivery. A handler may throw an exception,
     * may not be accessible due to security constraints or is not annotated properly.
     *
     * In any of all possible cases a publication error is created and passed to each of the registered error handlers.
     * A call to this method will add the given error handler to the chain
     */
    @Override
    public
    void addErrorHandler(final IPublicationErrorHandler errorHandler) {
        this.errorHandler.addErrorHandler(errorHandler);
    }


    /**
     * Check whether any asynchronous message publications are pending to be processed
     *
     * @return true if any unfinished message publications are found
     */
    @Override
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
    @Override
    public
    void shutdown() {
        this.syncPublication.shutdown();
        this.asyncPublication.shutdown();
        this.subscriptionManager.shutdown();
    }
}

