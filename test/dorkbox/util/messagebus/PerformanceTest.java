/*
 * Copyright 2015 dorkbox, llc
 */
package dorkbox.util.messagebus;

import dorkbox.util.messagebus.annotations.Handler;
import dorkbox.util.messagebus.common.ConcurrentExecutor;
import dorkbox.util.messagebus.error.IPublicationErrorHandler;
import dorkbox.util.messagebus.error.PublicationError;
import junit.framework.Assert;

/**
 * @author dorkbox, llc Date: 2/2/15
 */
public class PerformanceTest {
    // 15 == 32 * 1024
    public static final int REPETITIONS = Integer.getInteger("reps", 50) * 1000 * 1000;

    public static final int QUEUE_CAPACITY = 1 << Integer.getInteger("pow2.capacity", 17);

    public static final int CONCURRENCY_LEVEL = 2;

    protected static final IPublicationErrorHandler TestFailingHandler = new IPublicationErrorHandler() {
        @Override
        public void handleError(PublicationError error) {
            error.getCause().printStackTrace();
            Assert.fail();
        }

        @Override
        public void handleError(final String error, final Class<?> listenerClass) {
            // Printout the error itself
            System.out.println(new StringBuilder().append(error).append(": ").append(listenerClass.getSimpleName()).toString());
        }
    };

    public static void main(String[] args) throws Exception {
        final MessageBus bus = new MessageBus(CONCURRENCY_LEVEL);
        bus.getErrorHandler().addErrorHandler(TestFailingHandler);


        Listener listener1 = new Listener();
        bus.subscribe(listener1);


        ConcurrentExecutor.runConcurrent(new Runnable() {
            @Override
            public void run() {
                Long num = Long.valueOf(7L);
                while (true) {
                    bus.publish(num);
                }
            }
        }, CONCURRENCY_LEVEL);


        bus.shutdown();
    }

    public PerformanceTest() {
    }

    @SuppressWarnings("unused")
    public static class Listener {
        @Handler
        public void handleSync(Long o1) {
//            System.err.println(Long.toString(o1));
        }
    }
}
