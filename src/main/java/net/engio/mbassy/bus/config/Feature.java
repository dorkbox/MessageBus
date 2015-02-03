package net.engio.mbassy.bus.config;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.engio.mbassy.bus.IMessagePublication;
import net.engio.mbassy.bus.MessagePublication;
import net.engio.mbassy.listener.MetadataReader;

/**
 * A feature defines the configuration of a specific functionality of a message bus.
 *
 * @author bennidi
 *         Date: 8/29/14
 */
public interface Feature {


    class SyncPubSub implements Feature {

        public static final SyncPubSub Default(){
            return new SyncPubSub()
                    .setMetadataReader(new MetadataReader())
                    .setPublicationFactory(new MessagePublication.Factory());
        }

        private MessagePublication.Factory publicationFactory;
        private MetadataReader metadataReader;


        public MetadataReader getMetadataReader() {
            return metadataReader;
        }

        public SyncPubSub setMetadataReader(MetadataReader metadataReader) {
            this.metadataReader = metadataReader;
            return this;
        }

        /**
         * The message publication factory is used to wrap a published message
         * in a {@link MessagePublication} for processing.
         * @return The factory to be used by the bus to create the publications
         */
        public MessagePublication.Factory getPublicationFactory() {
            return publicationFactory;
        }

        public SyncPubSub setPublicationFactory(MessagePublication.Factory publicationFactory) {
            this.publicationFactory = publicationFactory;
            return this;
        }
    }

    class AsynchronousHandlerInvocation implements Feature {

        protected static final ThreadFactory MessageHandlerThreadFactory = new ThreadFactory() {

            private final AtomicInteger threadID = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = Executors.defaultThreadFactory().newThread(r);
                thread.setName("AsyncHandler-" + threadID.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };

        public static final AsynchronousHandlerInvocation Default(){
            int numberOfCores = Runtime.getRuntime().availableProcessors();
            return Default(numberOfCores, numberOfCores * 2);
        }

        public static final AsynchronousHandlerInvocation Default(int initialCoreThreads, int maximumCoreThreads){
            int numberOfCores = Runtime.getRuntime().availableProcessors();
            return new AsynchronousHandlerInvocation().setExecutor(new ThreadPoolExecutor(initialCoreThreads, maximumCoreThreads, 1,
                    TimeUnit.MINUTES, new LinkedBlockingQueue<Runnable>(), MessageHandlerThreadFactory));
        }

        private ExecutorService executor;

        public ExecutorService getExecutor() {
            return executor;
        }

        public AsynchronousHandlerInvocation setExecutor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }
    }

    class AsynchronousMessageDispatch implements Feature {

        protected static final ThreadFactory MessageDispatchThreadFactory = new ThreadFactory() {

            private final AtomicInteger threadID = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = Executors.defaultThreadFactory().newThread(r);
                thread.setDaemon(true);// do not prevent the JVM from exiting
                thread.setName("Dispatcher-" + threadID.getAndIncrement());
                return thread;
            }
        };

        public static final AsynchronousMessageDispatch Default(){
            return new AsynchronousMessageDispatch()
                .setNumberOfMessageDispatchers(2)
                .setDispatcherThreadFactory(MessageDispatchThreadFactory)
                .setMessageQueue(new LinkedBlockingQueue<IMessagePublication>(Integer.MAX_VALUE));
        }


        private int numberOfMessageDispatchers;
        private BlockingQueue<IMessagePublication> pendingMessages;
        private ThreadFactory dispatcherThreadFactory;

        public int getNumberOfMessageDispatchers() {
            return numberOfMessageDispatchers;
        }

        public AsynchronousMessageDispatch setNumberOfMessageDispatchers(int numberOfMessageDispatchers) {
            this.numberOfMessageDispatchers = numberOfMessageDispatchers;
            return this;
        }

        public BlockingQueue<IMessagePublication> getPendingMessages() {
            return pendingMessages;
        }

        public AsynchronousMessageDispatch setMessageQueue(BlockingQueue<IMessagePublication> pendingMessages) {
            this.pendingMessages = pendingMessages;
            return this;
        }

        public ThreadFactory getDispatcherThreadFactory() {
            return dispatcherThreadFactory;
        }

        public AsynchronousMessageDispatch setDispatcherThreadFactory(ThreadFactory dispatcherThreadFactory) {
            this.dispatcherThreadFactory = dispatcherThreadFactory;
            return this;
        }
    }


}
