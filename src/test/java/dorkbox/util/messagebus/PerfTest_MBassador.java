package dorkbox.util.messagebus;

import dorkbox.util.messagebus.annotations.Handler;


public class PerfTest_MBassador {
    public static final int REPETITIONS = 50 * 1000 * 100;
    public static final Integer TEST_VALUE = Integer.valueOf(777);

    private static final int concurrency = 10;

    public static void main(final String[] args) throws Exception {
        System.out.println("reps:" + REPETITIONS + "  Concurrency " + concurrency);

        final int warmupRuns = 4;
        final int runs = 5;

        MultiMBassador bus = new MultiMBassador(2);
        Listener listener1 = new Listener();
        bus.subscribe(listener1);

        long average = averageRun(warmupRuns, runs, bus, true, concurrency, REPETITIONS);

        System.out.format("summary,PublishPerfTest, %,d\n", average);
    }

    public static long averageRun(int warmUpRuns, int sumCount, MultiMBassador bus, boolean showStats, int concurrency, int repetitions) throws Exception {
        int runs = warmUpRuns + sumCount;
        final long[] results = new long[runs];
        for (int i = 0; i < runs; i++) {
            System.gc();
            results[i] = performanceRun(i, bus, showStats, concurrency, repetitions);
        }
        // only average last X results for summary
        long sum = 0;
        for (int i = warmUpRuns; i < runs; i++) {
            sum += results[i];
        }

        return sum/sumCount;
    }

    private static long performanceRun(int runNumber, MultiMBassador bus, boolean showStats, int concurrency, int repetitions) throws Exception {

        Producer[] producers = new Producer[concurrency];
        Thread[] threads = new Thread[concurrency*2];

        for (int i=0;i<concurrency;i++) {
            producers[i] = new Producer(bus, repetitions);
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

        for (int i=0;i<concurrency;i++) {
            if (producers[i].start < start) {
                start = producers[i].start;
            }

            if (producers[i].end > end) {
                end = producers[i].end;
            }
        }


        long duration = end - start;
        long ops = repetitions * 1_000_000_000L / duration;

        if (showStats) {
            System.out.format("%d - ops/sec=%,d\n", runNumber, ops);
        }
        return ops;
    }

    public static class Producer implements Runnable {
        private final MultiMBassador bus;
        volatile long start;
        volatile long end;
        private int repetitions;

        public Producer(MultiMBassador bus, int repetitions) {
            this.bus = bus;
            this.repetitions = repetitions;
        }

        @Override
        public void run() {
            MultiMBassador bus = this.bus;
            int i = this.repetitions;
            this.start = System.nanoTime();

            do {
                bus.publish(TEST_VALUE);
            } while (0 != --i);

            this.end = System.nanoTime();
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
