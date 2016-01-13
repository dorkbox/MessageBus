package dorkbox.util.messagebus.queuePerf;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TransferQueue;

@SuppressWarnings("Duplicates")
public
class Base_TransferQueue<T> {

    public
    void run(final int repetitions,
             final int producersCount,
             final int consumersCount,
             final int warmups,
             final int runs,
             final int bestRunsToAverage,
             final boolean showStats,
             final TransferQueue<T> queue,
             final T initialValue) throws Exception {

        for (int i = 0; i < warmups; i++) {
            performanceRun(i, queue,
                           false, producersCount, consumersCount, repetitions, initialValue);
        }

        final Long[] results = new Long[runs];
        for (int i = 0; i < runs; i++) {
            System.gc();
            results[i] = performanceRun(i, queue, showStats, producersCount, consumersCount, repetitions, initialValue);
        }

        // average best results for summary
        List<Long> list = Arrays.asList(results);
        Collections.sort(list);

        long sum = 0;
        // ignore the highest one
        int limit = runs - 1;
        for (int i = limit - bestRunsToAverage; i < limit; i++) {
            sum += list.get(i);
        }

        long average = sum / bestRunsToAverage;
        System.out.format("%s,%s  %dP/%dC %,d\n", this.getClass().getSimpleName(), queue.getClass().getSimpleName(),
                          producersCount, consumersCount, average);
    }


    /**
     * Benchmarks how long it takes to push X number of items total. If there are is 1P and 1C, then X items will be sent from a producer to
     * a consumer. Of there are NP and NC threads, then X/N (for a total of X) items will be sent.
     */
    private
    long performanceRun(final int runNumber,
                        final TransferQueue<T> queue,
                        final boolean showStats,
                        final int producersCount,
                        final int consumersCount,
                        int repetitions,
                        T initialValue) throws Exception {


        // make sure it's evenly divisible by both producers and consumers
        final int adjusted = repetitions / producersCount / consumersCount;

        int pRepetitions = adjusted * producersCount;
        int cRepetitions = adjusted * consumersCount;


        Producer[] producers = new Producer[producersCount];
        Consumer[] consumers = new Consumer[consumersCount];

        Thread[] pThreads = new Thread[producersCount];
        Thread[] cThreads = new Thread[consumersCount];

        for (int i=0;i<producersCount;i++) {
            producers[i] = new Producer<T>(queue, pRepetitions, initialValue);
        }
        for (int i=0;i<consumersCount;i++) {
            consumers[i] = new Consumer<T>(queue, cRepetitions);
        }

        for (int i=0;i<producersCount;i++) {
            pThreads[i] = new Thread(producers[i], "Producer " + i);
        }
        for (int i=0;i<consumersCount;i++) {
            cThreads[i] = new Thread(consumers[i], "Consumer " + i);
        }

        for (int i=0;i<consumersCount;i++) {
            cThreads[i].start();
        }
        for (int i=0;i<producersCount;i++) {
            pThreads[i].start();
        }


        for (int i=0;i<producersCount;i++) {
            pThreads[i].join();
        }
        for (int i=0;i<consumersCount;i++) {
            cThreads[i].join();
        }

        long start = Long.MAX_VALUE;
        long end = -1;

        for (int i=0;i<producersCount;i++) {
            if (producers[i].start < start) {
                start = producers[i].start;
            }
        }
        for (int i=0;i<consumersCount;i++) {
            if (consumers[i].end > end) {
                end = consumers[i].end;
            }
        }

        long duration = end - start;
        long ops = repetitions * 1000000000L / duration;

        if (showStats) {
            System.out.format("%d - ops/sec=%,d\n", runNumber, ops);
        }
        return ops;
    }

    public static class Producer<T> implements Runnable {
        private final TransferQueue<T> queue;
        volatile long start;
        private int repetitions;
        private final T initialValue;

        public Producer(TransferQueue<T> queue, int repetitions, T initialValue) {
            this.queue = queue;
            this.repetitions = repetitions;
            this.initialValue = initialValue;
        }

        @Override
        public void run() {
            TransferQueue<T> producer = this.queue;
            int i = this.repetitions;
            this.start = System.nanoTime();
            final T initialValue = this.initialValue;

            try {
                do {
                    producer.transfer(initialValue);
                } while (0 != --i);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static class Consumer<T> implements Runnable {
        private final TransferQueue<T> queue;
        Object result;
        volatile long end;
        private int repetitions;

        public Consumer(TransferQueue<T> queue, int repetitions) {
            this.queue = queue;
            this.repetitions = repetitions;
        }

        public void run() {
            TransferQueue<T> consumer = this.queue;
            int i = this.repetitions;

            T result = null;
            try {
                do {
                    result = consumer.take();
                } while (0 != --i);
            } catch (Exception e) {
                e.printStackTrace();
            }

            this.result = result;
            this.end = System.nanoTime();
        }
    }
}
