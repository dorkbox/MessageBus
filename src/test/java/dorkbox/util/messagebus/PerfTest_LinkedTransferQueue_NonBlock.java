package dorkbox.util.messagebus;

import java.util.concurrent.LinkedTransferQueue;

public class PerfTest_LinkedTransferQueue_NonBlock {
    public static final int REPETITIONS = 50 * 1000 * 100;
    public static final Integer TEST_VALUE = Integer.valueOf(777);

    public static final int QUEUE_CAPACITY = 1 << 17;

    private static final int concurrency = 4;

    public static void main(final String[] args) throws Exception {
        System.out.println("reps:" + REPETITIONS + "  Concurrency " + concurrency);

        final int warmupRuns = 5;
        final int runs = 5;

        long average = 0;

        final LinkedTransferQueue queue = new LinkedTransferQueue();
        average = averageRun(warmupRuns, runs, queue, true, concurrency, REPETITIONS);

        System.out.format("summary,QueuePerfTest,%s %,d\n", queue.getClass().getSimpleName(), average);
    }

    public static long averageRun(int warmUpRuns, int sumCount, LinkedTransferQueue queue, boolean showStats, int concurrency, int repetitions) throws Exception {
        int runs = warmUpRuns + sumCount;
        final long[] results = new long[runs];
        for (int i = 0; i < runs; i++) {
            System.gc();
            results[i] = performanceRun(i, queue, showStats, concurrency, repetitions);
        }
        // only average last X results for summary
        long sum = 0;
        for (int i = warmUpRuns; i < runs; i++) {
            sum += results[i];
        }

        return sum/sumCount;
    }

    private static long performanceRun(int runNumber, LinkedTransferQueue queue, boolean showStats, int concurrency, int repetitions) throws Exception {

        Producer[] producers = new Producer[concurrency];
        Consumer[] consumers = new Consumer[concurrency];
        Thread[] threads = new Thread[concurrency*2];

        for (int i=0;i<concurrency;i++) {
            producers[i] = new Producer(queue, repetitions);
            consumers[i] = new Consumer(queue, repetitions);
        }

        for (int j=0,i=0;i<concurrency;i++,j+=2) {
            threads[j] = new Thread(producers[i], "Producer " + i);
            threads[j+1] = new Thread(consumers[i], "Consumer " + i);
        }

        for (int i=0;i<concurrency*2;i+=2) {
            threads[i].start();
            threads[i+1].start();
        }

        for (int i=0;i<concurrency*2;i+=2) {
            threads[i].join();
            threads[i+1].join();
        }

        long start = Long.MAX_VALUE;
        long end = -1;

        for (int i=0;i<concurrency;i++) {
            if (producers[i].start < start) {
                start = producers[i].start;
            }

            if (consumers[i].end > end) {
                end = consumers[i].end;
            }
        }


        long duration = end - start;
        long ops = repetitions * 1_000_000_000L / duration;
        String qName = queue.getClass().getSimpleName();

        if (showStats) {
            System.out.format("%d - ops/sec=%,d - %s\n", runNumber, ops, qName);
        }
        return ops;
    }

    public static class Producer implements Runnable {
        private final LinkedTransferQueue queue;
        volatile long start;
        private int repetitions;

        public Producer(LinkedTransferQueue queue, int repetitions) {
            this.queue = queue;
            this.repetitions = repetitions;
        }

        @Override
        public void run() {
            LinkedTransferQueue producer = this.queue;
            int i = this.repetitions;
            this.start = System.nanoTime();

            do {
                while (!producer.offer(TEST_VALUE)) {
                    Thread.yield();
                }
            } while (0 != --i);
        }
    }

    public static class Consumer implements Runnable {
        private final LinkedTransferQueue queue;
        Object result;
        volatile long end;
        private int repetitions;

        public Consumer(LinkedTransferQueue queue, int repetitions) {
            this.queue = queue;
            this.repetitions = repetitions;
        }

        @Override
        public void run() {
            LinkedTransferQueue consumer = this.queue;
            Object result = null;
            int i = this.repetitions;

            do {
                while (null == (result = consumer.poll())) {
                    Thread.yield();
                }
            } while (0 != --i);

            this.result = result;
            this.end = System.nanoTime();
        }
    }
}
