package dorkbox.util.messagebus.queuePerf;

import dorkbox.util.messagebus.MTAQ_Accessor;
import dorkbox.util.messagebus.MultiNode;

public
class PerfTest_MpmcTransferArrayQueue_Block {
//    static {
//        System.setProperty("sparse.shift", "2");
//    }

    public static final int REPETITIONS = 50 * 1000 * 100;
    public static final Integer TEST_VALUE = Integer.valueOf(777);

    private static final int concurrency = 2;

    public static
    void main(final String[] args) throws Exception {
//        System.out.println(VMSupport.vmDetails());
//        System.out.println(ClassLayout.parseClass(MultiNode.class).toPrintable());

        System.out.println("reps:" + REPETITIONS + "  Concurrency " + concurrency);

        final int warmupRuns = 4;
        final int runs = 5;

        final MTAQ_Accessor queue = new MTAQ_Accessor(concurrency);
        long average = averageRun(warmupRuns, runs, queue, true, concurrency, REPETITIONS);

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

    public static
    long averageRun(int warmUpRuns, int sumCount, MTAQ_Accessor queue, boolean showStats, int concurrency, int repetitions)
                    throws Exception {
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

        return sum / sumCount;
    }

    private static
    long performanceRun(int runNumber, MTAQ_Accessor queue, boolean showStats, int concurrency, int repetitions) throws Exception {

        Producer[] producers = new Producer[concurrency];
        Consumer[] consumers = new Consumer[concurrency];
        Thread[] threads = new Thread[concurrency * 2];

        for (int i = 0; i < concurrency; i++) {
            producers[i] = new Producer(queue, repetitions);
            consumers[i] = new Consumer(queue, repetitions);
        }

        for (int j = 0, i = 0; i < concurrency; i++, j += 2) {
            threads[j] = new Thread(producers[i], "Producer " + i);
            threads[j + 1] = new Thread(consumers[i], "Consumer " + i);
        }

        for (int i = 0; i < concurrency * 2; i += 2) {
            threads[i].start();
            threads[i + 1].start();
        }

        for (int i = 0; i < concurrency * 2; i += 2) {
            threads[i].join();
            threads[i + 1].join();
        }

        long start = Long.MAX_VALUE;
        long end = -1;

        for (int i = 0; i < concurrency; i++) {
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

    public static
    class Producer implements Runnable {
        private final MTAQ_Accessor queue;
        volatile long start;
        private int repetitions;

        public
        Producer(MTAQ_Accessor queue, int repetitions) {
            this.queue = queue;
            this.repetitions = repetitions;
        }

        @Override
        public
        void run() {
            MTAQ_Accessor producer = this.queue;
            int i = this.repetitions;
            this.start = System.nanoTime();

            try {
                do {
                    producer.transfer(TEST_VALUE, 1);
                } while (0 != --i);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }


    public static
    class Consumer implements Runnable {
        private final MTAQ_Accessor queue;
        Object result;
        volatile long end;
        private int repetitions;

        public
        Consumer(MTAQ_Accessor queue, int repetitions) {
            this.queue = queue;
            this.repetitions = repetitions;
        }

        @Override
        public
        void run() {
            MTAQ_Accessor consumer = this.queue;
            int i = this.repetitions;

            MultiNode node = new MultiNode();
            try {
                do {
                    consumer.take(node);
                } while (0 != --i);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            MultiNode.lvMessageType(node); // LoadLoad
            this.result = MultiNode.lpItem1(node);

            this.end = System.nanoTime();
        }
    }
}
