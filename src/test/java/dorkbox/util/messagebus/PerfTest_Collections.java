package dorkbox.util.messagebus;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedTransferQueue;

import dorkbox.util.messagebus.annotations.Handler;
import dorkbox.util.messagebus.common.ConcurrentHashMapV8;
import dorkbox.util.messagebus.common.StrongConcurrentSet;
import dorkbox.util.messagebus.common.StrongConcurrentSetV8;
import dorkbox.util.messagebus.common.thread.ConcurrentLinkedQueue2;
import dorkbox.util.messagebus.common.thread.ConcurrentSet;
import dorkbox.util.messagebus.listener.MessageHandler;
import dorkbox.util.messagebus.listener.MessageListener;
import dorkbox.util.messagebus.listener.MetadataReader;
import dorkbox.util.messagebus.subscription.Subscription;


public class PerfTest_Collections {
    public static final int REPETITIONS = 10 * 1000 * 100;
    public static final Integer TEST_VALUE = Integer.valueOf(777);

    private static final float LOAD_FACTOR = 0.8F;
    private static MessageListener messageListener = new MetadataReader().getMessageListener(Listener.class);

    public static void main(final String[] args) throws Exception {
        final int size = 16;

        System.out.println("reps:" + REPETITIONS + "  size: " + size);

        // have to warm-up the JVM.
        System.err.print("\nWarming up JVM.");
//        for (int i=0;i<2;i++) {
            bench(size, new ConcurrentLinkedQueue<Subscription>(), false);
            System.err.print(".");
            bench(size, new ArrayList<Subscription>(size*2), false);
            System.err.print(".");
            bench(size, new ArrayDeque<Subscription>(size*2), false);
            System.err.print(".");
            bench(size, new ConcurrentLinkedQueue<Subscription>(), false);
            System.err.print(".");
            bench(size, new LinkedList<Subscription>(), false);
            System.err.print(".");
//        }
        System.err.println("Done");

        bench(size, new ConcurrentLinkedQueue2<Subscription>());
        bench(size, new ConcurrentSet<Subscription>(size*2, LOAD_FACTOR, 5));
        bench(size, Collections.<Subscription>newSetFromMap(new ConcurrentHashMapV8<Subscription, Boolean>(size*2, LOAD_FACTOR, 1)));
        bench(size, new ArrayList<Subscription>(size*2));
        bench(size, new ConcurrentLinkedQueue<Subscription>());
        bench(size, new LinkedTransferQueue<Subscription>());
        bench(size, new ArrayDeque<Subscription>(size*2));
        bench(size, new ConcurrentLinkedQueue<Subscription>());
        bench(size, new LinkedList<Subscription>());
        bench(size, new StrongConcurrentSetV8<Subscription>(size*2, LOAD_FACTOR));
        bench(size, new StrongConcurrentSet<Subscription>(size*2, LOAD_FACTOR));
        bench(size, new HashSet<Subscription>());
//        bench(size, new ConcurrentSkipListSet<Subscription>()); // needs comparable
    }

    public static void bench(final int size, Collection<Subscription> set) throws Exception {
        bench(size, set, true);
    }
    public static void bench(final int size, Collection<Subscription> set, boolean showOutput) throws Exception {
        final int warmupRuns = 2;
        final int runs = 3;

        for (int i=0;i<size;i++) {
            for (MessageHandler x : messageListener.getHandlers()) {
                set.add(new Subscription(x));
            }
        }

        for (int i=2;i<6;i++) {
            long average = averageRun(warmupRuns, runs, set, false, i, REPETITIONS);
            if (showOutput) {
                System.out.format("summary,IteratorPerfTest,%s - %,d  (%d)\n", set.getClass().getSimpleName(), average, i);
            }
        }

        set.clear();
    }

    public static long averageRun(int warmUpRuns, int sumCount, Collection<Subscription> set, boolean showStats, int concurrency, int repetitions) throws Exception {
        int runs = warmUpRuns + sumCount;
        final long[] results = new long[runs];
        for (int i = 0; i < runs; i++) {
            WeakReference<Object> weakReference = new WeakReference<>(new Object());
            while (weakReference.get() != null) {
                System.gc();
                Thread.sleep(100L);
            }
            results[i] = performanceRun(i, set, showStats, concurrency, repetitions);
        }
        // only average last X results for summary
        long sum = 0;
        for (int i = warmUpRuns; i < runs; i++) {
            sum += results[i];
        }

        return sum/sumCount;
    }

    private static long performanceRun(int runNumber, Collection<Subscription> set, boolean showStats, int concurrency, int repetitions) throws Exception {

        Producer[] producers = new Producer[concurrency];
        Thread[] threads = new Thread[concurrency*2];

        for (int i=0;i<concurrency;i++) {
            producers[i] = new Producer(set, repetitions);
            threads[i] = new Thread(producers[i], "Producer " + i);
        }

        for (int i=0;i<concurrency;i++) {
            threads[i].start();
        }

        for (int i=0;i<concurrency;i++) {
            threads[i].join();
        }

        long start = Long.MAX_VALUE;
        long end = -1;
        long count = 0;

        for (int i=0;i<concurrency;i++) {
            if (producers[i].start < start) {
                start = producers[i].start;
            }

            if (producers[i].end > end) {
                end = producers[i].end;
            }

            count += producers[i].count;
        }


        long duration = end - start;
        long ops = repetitions * 1_000_000_000L / duration;

        if (showStats) {
            System.out.format("%d (%d) - ops/sec=%,d\n", runNumber, count, ops);
        }
        return ops;
    }

    public static class Producer implements Runnable {
        private final Collection<Subscription> set;
        volatile long start;
        volatile long end;
        private int repetitions;
        volatile int count;

        public Producer(Collection<Subscription> set, int repetitions) {
            this.set = set;
            this.repetitions = repetitions;
        }

        @SuppressWarnings("unused")
        @Override
        public void run() {
            Collection<Subscription> set = this.set;
            int i = this.repetitions;
            this.start = System.nanoTime();

//            Entry<Subscription> current;
//            Subscription sub;
            int count = 0;

            do {
                for (Subscription sub : set) {
                    //                    if (sub.acceptsSubtypes()) {
//                        count--;
//                    } else {
                    count++;
//                    }
                }

//                current = set.head;
//                while (current != null) {
//                    sub = current.getValue();
//                    current = current.next();
//
////                    count++;
//                }
            } while (0 != --i);

            this.end = System.nanoTime();
            this.count = count;
        }
    }

    @SuppressWarnings("unused")
    public static class Listener {
        @Handler
        public void handleSync(Integer o1) {
        }

        @Handler(acceptVarargs=true)
        public void handleSync(Object... o) {
        }
    }
}
