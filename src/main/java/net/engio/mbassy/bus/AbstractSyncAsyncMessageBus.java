package net.engio.mbassy.bus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import net.engio.mbassy.bus.common.IMessageBus;
import net.engio.mbassy.bus.config.Feature;
import net.engio.mbassy.bus.config.IBusConfiguration;
import net.engio.mbassy.bus.error.PublicationError;

/**
 * The base class for all message bus implementations with support for asynchronous message dispatch
 *
 * @param <T>
 * @param <P>
 */
public abstract class AbstractSyncAsyncMessageBus<T>
        extends AbstractPubSubSupport<T> implements IMessageBus<T> {

    // executor for asynchronous message handlers
    private final ExecutorService executor;

    // all threads that are available for asynchronous message dispatching
    private final List<Thread> dispatchers;

    // all pending messages scheduled for asynchronous dispatch are queued here
    private final BlockingQueue<IMessagePublication> pendingMessages;

    protected AbstractSyncAsyncMessageBus(IBusConfiguration configuration) {
        super(configuration);

        // configure asynchronous message dispatch
        Feature.AsynchronousMessageDispatch asyncDispatch = configuration.getFeature(Feature.AsynchronousMessageDispatch.class);
        this.pendingMessages = asyncDispatch.getPendingMessages();
        this.dispatchers = new ArrayList<Thread>(asyncDispatch.getNumberOfMessageDispatchers());
        initDispatcherThreads(asyncDispatch);

        // configure asynchronous handler invocation
        Feature.AsynchronousHandlerInvocation asyncInvocation = configuration.getFeature(Feature.AsynchronousHandlerInvocation.class);
        this.executor = asyncInvocation.getExecutor();
    }

    // initialize the dispatch workers
    private void initDispatcherThreads(Feature.AsynchronousMessageDispatch configuration) {
        for (int i = 0; i < configuration.getNumberOfMessageDispatchers(); i++) {
            // each thread will run forever and process incoming
            // message publication requests
            Thread dispatcher = configuration.getDispatcherThreadFactory().newThread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        IMessagePublication publication = null;
                        try {
                            publication = AbstractSyncAsyncMessageBus.this.pendingMessages.take();
                            publication.execute();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch(Throwable t){
                            handlePublicationError(new PublicationError(t, "Error in asynchronous dispatch",publication));
                        }
                    }
                }
            });
            dispatcher.setName("Message dispatcher");
            this.dispatchers.add(dispatcher);
            dispatcher.start();
        }
    }


    // this method queues a message delivery request
    protected IMessagePublication addAsynchronousPublication(IMessagePublication publication) {
        try {
            this.pendingMessages.put(publication);
            return publication.markScheduled();
        } catch (InterruptedException e) {
            handlePublicationError(new PublicationError(e, "Error while adding an asynchronous message publication", publication));
            return publication;
        }
    }

    // this method queues a message delivery request
    protected IMessagePublication addAsynchronousPublication(IMessagePublication publication, long timeout, TimeUnit unit) {
        try {
            return this.pendingMessages.offer(publication, timeout, unit)
                    ? publication.markScheduled()
                    : publication;
        } catch (InterruptedException e) {
            handlePublicationError(new PublicationError(e, "Error while adding an asynchronous message publication", publication));
            return publication;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        shutdown();
    }

    @Override
    public void shutdown() {
        for (Thread dispatcher : this.dispatchers) {
            dispatcher.interrupt();
        }
        if(this.executor != null) {
            this.executor.shutdown();
        }
    }

    @Override
    public boolean hasPendingMessages() {
        return this.pendingMessages.size() > 0;
    }

    @Override
    public Executor getExecutor() {
        return this.executor;
    }

}
