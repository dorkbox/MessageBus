/*
 * Copyright 2015 dorkbox, llc
 */
package dorkbox.util.messagebus;

import junit.framework.Assert;

import com.lmax.disruptor.MessageHolder;

import dorkbox.util.messagebus.common.simpleq.HandlerFactory;
import dorkbox.util.messagebus.common.simpleq.SimpleQueue;
import dorkbox.util.messagebus.error.IPublicationErrorHandler;
import dorkbox.util.messagebus.error.PublicationError;

/**
 * @author dorkbox, llc Date: 2/2/15
 */
public class PerformanceTest {
    // 15 == 32 * 1024
    public static final int REPETITIONS = Integer.getInteger("reps", 50) * 1000 * 1000;
    public static final Integer TEST_VALUE = Integer.valueOf(777);

    public static final int QUEUE_CAPACITY = 1 << Integer.getInteger("pow2.capacity", 17);

    public static final int CONCURRENCY_LEVEL = 1;

    private static long count = 0;

    protected static final IPublicationErrorHandler TestFailingHandler = new IPublicationErrorHandler() {
        @Override
        public void handleError(PublicationError error) {
            error.getCause().printStackTrace();
            Assert.fail();
        }
    };

    public static void main(String[] args) throws Exception {
        System.out.println("capacity:" + QUEUE_CAPACITY + " reps:" + REPETITIONS);

        HandlerFactory<MessageHolder> factory = new HandlerFactory<MessageHolder>() {
            @Override
            public MessageHolder newInstance() {
                return new MessageHolder();
            }
        };

        final SimpleQueue<MessageHolder> queue = new SimpleQueue<MessageHolder>(QUEUE_CAPACITY, factory);

        final long[] results = new long[20];
        for (int i = 0; i < 20; i++) {
            System.gc();
            results[i] = performanceRun(i, queue);
        }
        // only average last 10 results for summary
        long sum = 0;
        for (int i = 10; i < 20; i++) {
            sum += results[i];
        }
        System.out.format("summary,QueuePerfTest,%s,%d\n", queue.getClass().getSimpleName(), sum / 10);
    }

    private static long performanceRun(int runNumber, SimpleQueue<MessageHolder> queue) throws Exception {
//        for (int i=0;i<CONCURRENCY_LEVEL;i++) {
            Producer p = new Producer(queue);
            Thread thread = new Thread(p);
            thread.start(); // producer will timestamp start
//        }

        SimpleQueue<MessageHolder> consumer = queue;
//        Node<Integer> result;
        int i = REPETITIONS;
        int queueEmpty = 0;
        MessageHolder messageHolder = new MessageHolder();
        do {
            consumer.take(messageHolder);
        } while (0 != --i);
        long end = System.nanoTime();

        thread.join();
        long duration = end - p.start;
        long ops = REPETITIONS * 1000L * 1000L * 1000L / duration;
        String qName = queue.getClass().getSimpleName();
        Integer finalMessage = (Integer) messageHolder.message1;
        System.out.format("%d - ops/sec=%,d - %s result=%d failed.poll=%d failed.offer=%d\n", runNumber, ops,
                qName, finalMessage, queueEmpty, p.queueFull);
        return ops;
    }

    public static class Producer implements Runnable {
        private final SimpleQueue<MessageHolder> queue;
        int queueFull = 0;
        long start;

        public Producer(SimpleQueue<MessageHolder> queue) {
            this.queue = queue;
        }

        @Override
        public void run() {
            SimpleQueue<MessageHolder> producer = this.queue;
            int i = REPETITIONS;
            int f = 0;
            long s = System.nanoTime();
            try {
                do {
                    producer.transfer(TEST_VALUE);
                } while (0 != --i);
            } catch (InterruptedException ignored) {
            }
            this.queueFull = f;
            this.start = s;
        }
    }



    public PerformanceTest() {
    }

//    public void testMultiMessageSending() {
//        final MultiMBassador bus = new MultiMBassador(4);
//        bus.addErrorHandler(TestFailingHandler);
//
//
//        Listener listener1 = new Listener();
//        bus.subscribe(listener1);
//
//
//        ConcurrentExecutor.runConcurrent(new Runnable() {
//            @Override
//            public void run() {
//                long num = 0;
//                while (num < Long.MAX_VALUE) {
//                    bus.publishAsync(num++);
//                }
//            }}, 4);
//
//
//        bus.shutdown();
//        System.err.println("Count: " + count);
//    }
//
//    @SuppressWarnings("unused")
//    public static class Listener {
//        @Handler
//        public void handleSync(Long o1) {
////            System.err.println(Long.toString(o1));
//              count++;
//        }
//    }
}
