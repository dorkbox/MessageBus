package dorkbox.util.messagebus;

import dorkbox.util.messagebus.common.simpleq.jctools.MpmcTransferArrayQueue;

public class PerfTest_MpmcTransferArrayQueue_NonBlock {
    public static final int REPETITIONS = 50 * 1000 * 1000;
    public static final Integer TEST_VALUE = Integer.valueOf(777);

    public static final int QUEUE_CAPACITY = 1 << 17;

    private static final int concurrency = 1;

    public static void main(final String[] args) throws Exception {
        final int warmupRuns = 5;
        final int runs = 5;

        long average = 0;

        final MpmcTransferArrayQueue queue = new MpmcTransferArrayQueue(concurrency, QUEUE_CAPACITY);
        average = averageRun(warmupRuns, runs, queue);

//        SimpleQueue.INPROGRESS_SPINS = 64;
//        SimpleQueue.POP_SPINS = 512;
//        SimpleQueue.PUSH_SPINS = 512;
//        SimpleQueue.PARK_SPINS = 512;
//
//        for (int i = 128; i< 10000;i++) {
//            int full = 2*i;
//
//            final SimpleQueue queue = new SimpleQueue(QUEUE_CAPACITY);
//            SimpleQueue.PARK_SPINS = full;
//
//
//            long newAverage = averageRun(warmupRuns, runs, queue);
//            if (newAverage > average) {
//                average = newAverage;
//                System.err.println("Best value: " + i + "  : " + newAverage);
//            }
//        }


        System.out.format("summary,QueuePerfTest,%s %,d\n", queue.getClass().getSimpleName(), average);
    }

    private static long averageRun(int warmUpRuns, int sumCount, MpmcTransferArrayQueue queue) throws Exception {
        int runs = warmUpRuns + sumCount;
        final long[] results = new long[runs];
        for (int i = 0; i < runs; i++) {
            System.gc();
            results[i] = performanceRun(i, queue);
        }
        // only average last X results for summary
        long sum = 0;
        for (int i = warmUpRuns; i < runs; i++) {
            sum += results[i];
        }

        return sum/sumCount;
    }

    private static long performanceRun(int runNumber, MpmcTransferArrayQueue queue) throws Exception {

        Producer[] producers = new Producer[concurrency];
        Consumer[] consumers = new Consumer[concurrency];
        Thread[] threads = new Thread[concurrency*2];

        for (int i=0;i<concurrency;i++) {
            producers[i] = new Producer(queue);
            consumers[i] = new Consumer(queue);
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
        long ops = REPETITIONS * 1_000_000_000L / duration;
        String qName = queue.getClass().getSimpleName();

        System.out.format("%d - ops/sec=%,d - %s\n", runNumber, ops, qName);
        return ops;
    }

    public static class Producer implements Runnable {
        private final MpmcTransferArrayQueue queue;
        volatile long start;

        public Producer(MpmcTransferArrayQueue queue) {
            this.queue = queue;
        }

        @Override
        public void run() {
            MpmcTransferArrayQueue producer = this.queue;
            int i = REPETITIONS;
            this.start = System.nanoTime();

            do {
                while (!producer.offer(TEST_VALUE)) {
                    Thread.yield();
                }
            } while (0 != --i);
        }
    }

    public static class Consumer implements Runnable {
        private final MpmcTransferArrayQueue queue;
        Object result;
        volatile long end;

        public Consumer(MpmcTransferArrayQueue queue) {
            this.queue = queue;
        }

        @Override
        public void run() {
            MpmcTransferArrayQueue consumer = this.queue;
            Object result = null;
            int i = REPETITIONS;

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
