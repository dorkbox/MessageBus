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
package dorkbox.util.messagebus;

import dorkbox.util.messagebus.error.DefaultErrorHandler;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.publication.Publisher;
import dorkbox.util.messagebus.publication.PublisherExact;
import dorkbox.util.messagebus.publication.PublisherExactWithSuperTypes;
import dorkbox.util.messagebus.subscription.SubscriptionManager;
import dorkbox.util.messagebus.synchrony.AsyncABQ;
import dorkbox.util.messagebus.synchrony.AsyncABQ_noGc;
import dorkbox.util.messagebus.synchrony.AsyncDisruptor;
import dorkbox.util.messagebus.synchrony.Sync;
import dorkbox.util.messagebus.synchrony.Synchrony;

/**
 * The base class for all message bus implementations with support for asynchronous message dispatch.
 *
 * See this post for insight on how it operates:  http://psy-lob-saw.blogspot.com/2012/12/atomiclazyset-is-performance-win-for.html
 * tldr; we use single-writer-principle + lazySet/get
 *
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public
class MessageBus implements IMessageBus {

    public static boolean useDisruptorForAsyncPublish = true;
    public static boolean useAsmForDispatch = true;

    public static boolean useNoGarbageVersionOfABQ = true;

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

    private final ErrorHandlingSupport errorHandler;

    private final SubscriptionManager subscriptionManager;

    private final Publisher publisher;
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
    MessageBus(int numberOfThreads) {
        this(PublishMode.ExactWithSuperTypes, numberOfThreads);
    }

    /**
     * By default, will use half of CPUs available for dispatching async messages
     *
     * @param publishMode     Specifies which publishMode to operate the publication of messages.
     */
    public
    MessageBus(final PublishMode publishMode) {
        this(publishMode, Runtime.getRuntime().availableProcessors());
    }

    /**
     * @param publishMode     Specifies which publishMode to operate the publication of messages.
     * @param numberOfThreads how many threads to use for dispatching async messages
     */
    public
    MessageBus(final PublishMode publishMode, int numberOfThreads) {
        // round to the nearest power of 2
        numberOfThreads = 1 << (32 - Integer.numberOfLeadingZeros(getMinNumberOfThreads(numberOfThreads) - 1));

        this.errorHandler = new DefaultErrorHandler();

        /**
         * Will subscribe and publish using all provided parameters in the method signature (for subscribe), and arguments (for publish)
         */
        this.subscriptionManager = new SubscriptionManager(numberOfThreads);

        switch (publishMode) {
            case Exact:
                publisher = new PublisherExact(errorHandler, subscriptionManager);
                break;

            case ExactWithSuperTypes:
            default:
                publisher = new PublisherExactWithSuperTypes(errorHandler, subscriptionManager);
                break;
        }

        syncPublication = new Sync();

        // the disruptor is preferred, but if it cannot be loaded -- we want to try to continue working, hence the use of ArrayBlockingQueue
        if (useDisruptorForAsyncPublish) {
            asyncPublication = new AsyncDisruptor(numberOfThreads, errorHandler, publisher, syncPublication);
        } else {
            if (useNoGarbageVersionOfABQ) {
                // no garbage is created, but this is slow (but faster than other messagebus implementations)
                asyncPublication = new AsyncABQ_noGc(numberOfThreads, errorHandler, publisher, syncPublication);
            }
            else {
                // garbage is created, but this is fast
                asyncPublication = new AsyncABQ(numberOfThreads, errorHandler, publisher, syncPublication);
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

    @Override
    public
    void subscribe(final Object listener) {
        if (listener == null) {
            return;
        }

        // single writer principle using synchronised
        subscriptionManager.subscribe(listener);
    }

    @Override
    public
    void unsubscribe(final Object listener) {
        if (listener == null) {
            return;
        }

        // single writer principle using synchronised
        subscriptionManager.unsubscribe(listener);
    }

    @Override
    public
    void publish(final Object message) {
        publisher.publish(syncPublication, message);
    }

    @Override
    public
    void publish(final Object message1, final Object message2) {
        publisher.publish(syncPublication, message1, message2);
    }

    @Override
    public
    void publish(final Object message1, final Object message2, final Object message3) {
        publisher.publish(syncPublication, message1, message2, message3);
    }

    @Override
    public
    void publishAsync(final Object message) {
        publisher.publish(asyncPublication, message);
    }

    @Override
    public
    void publishAsync(final Object message1, final Object message2) {
        publisher.publish(asyncPublication, message1, message2);
    }

    @Override
    public
    void publishAsync(final Object message1, final Object message2, final Object message3) {
        publisher.publish(asyncPublication, message1, message2, message3);
    }

    @Override
    public final
    boolean hasPendingMessages() {
        return asyncPublication.hasPendingMessages();
    }

    @Override
    public final
    ErrorHandlingSupport getErrorHandler() {
        return errorHandler;
    }

    @Override
    public
    void start() {
        errorHandler.init();
        asyncPublication.start();
    }

    @Override
    public
    void shutdown() {
        this.syncPublication.shutdown();
        this.asyncPublication.shutdown();
        this.subscriptionManager.shutdown();
    }
}
