package net.engio.mbassy.multi.disruptor;

import java.util.concurrent.ExecutorService;

import com.lmax.disruptor.WorkHandler;

/**
 * @author dorkbox, llc Date: 2/2/15
 */
public class DispatchProcessor implements WorkHandler<DispatchHolder> {
    private final long ordinal;
    private final long numberOfConsumers;

    private final ExecutorService invoke_Executor;

    public DispatchProcessor(final long ordinal, final long numberOfConsumers,
                             final ExecutorService invoke_Executor) {
        this.ordinal = ordinal;
        this.numberOfConsumers = numberOfConsumers;
        this.invoke_Executor = invoke_Executor;
    }




    @Override
    public void onEvent(DispatchHolder event) throws Exception {
//        if (sequence % this.numberOfConsumers == this.ordinal) {
//            System.err.println("handoff -" + this.ordinal);

            this.invoke_Executor.submit(event.runnable);
//            event.runnable.run();

            // Process the event
            // switch (event.messageType) {
            // case ONE: {
//            publish(event.message1);
//            event.message1 = null; // cleanup
            // return;
            // }
            // case TWO: {
            // // publisher.publish(this.message1, this.message2);
            // event.message1 = null; // cleanup
            // event.message2 = null; // cleanup
            // return;
            // }
            // case THREE: {
            // // publisher.publish(this.message1, this.message2, this.message3);
            // event.message1 = null; // cleanup
            // event.message2 = null; // cleanup
            // event.message3 = null; // cleanup
            // return;
            // }
            // case ARRAY: {
            // // publisher.publish(this.messages);
            // event.messages = null; // cleanup
            // return;
            // }
            // }

//        }
    }

//    private void publish(Object message) {
//        Class<?> messageClass = message.getClass();
//
//        SubscriptionManager manager = this.subscriptionManager;
//        Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass);
//
//        try {
//            boolean empty = subscriptions.isEmpty();
//            if (empty) {
//                // Dead Event
//                subscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
//
//                message = new DeadMessage(message);
//
//                empty = subscriptions.isEmpty();
//            }
//
//            if (!empty) {
////                // put this on the disruptor ring buffer
////                final RingBuffer<MessageHolder> ringBuffer = this.invoke_RingBuffer;
////
////                // setup the job
////                final long seq = ringBuffer.next();
////                try {
////                    MessageHolder eventJob = ringBuffer.get(seq);
////                    eventJob.messageType = MessageType.ONE;
////                    eventJob.message1 = message;
////                    eventJob.subscriptions = subscriptions;
////                } catch (Throwable e) {
////                    this.publisher.handlePublicationError(new PublicationError()
////                                                .setMessage("Error while adding an asynchronous message")
////                                                .setCause(e)
////                                                .setPublishedObject(message));
////                } finally {
////                    // always publish the job
////                    ringBuffer.publish(seq);
////                }
//
//
//
////                // this is what gets parallelized. The collection IS NOT THREAD SAFE, but it's contents are
////                ObjectPoolHolder<MessageHolder> messageHolder = this.pool.take();
////                MessageHolder value = messageHolder.getValue();
//                MessageHolder messageHolder = new MessageHolder();
//                messageHolder.subscriptions= subscriptions;
//                messageHolder.messageType = MessageType.ONE;
//                messageHolder.message1 = message;
//
////                this.queue.put(messageHolder);
//
////                int counter = 200;
////                while (!this.queue.offer(messageHolder)) {
////                    if (counter > 100) {
////                        --counter;
////                    } else if (counter > 0) {
////                        --counter;
////                        Thread.yield();
////                    } else {
////                        LockSupport.parkNanos(1L);
////                    }
////                }
//            }
//        } catch (Throwable e) {
//            this.publisher.handlePublicationError(new PublicationError().setMessage("Error during publication of message").setCause(e)
//                            .setPublishedObject(message));
//        }
//    }

}
