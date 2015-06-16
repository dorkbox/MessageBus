package dorkbox.util.messagebus.queuePerf;

import java.util.concurrent.LinkedBlockingQueue;

public class PerfTest_LinkedBlockingQueue {
    public static final int REPETITIONS = 50 * 1000 * 100;
    public static final Integer TEST_VALUE = Integer.valueOf(777);


    public static void main(final String[] args) throws Exception {
        System.out.println("reps:" + REPETITIONS);

        final int warmupRuns = 4;
        final int runs = 3;

        for (int concurrency = 1; concurrency < 5; concurrency++) {
            final LinkedBlockingQueue queue = new LinkedBlockingQueue(Integer.MAX_VALUE);
            long average = PerfTest_LinkedBlockingQueue_Block.averageRun(warmupRuns, runs, queue, false, concurrency, REPETITIONS);
            System.out.format("PerfTest_LinkedBlockingQueue_Block %,d (%dx%d)\n", average, concurrency, concurrency);
        }

        for (int concurrency = 1; concurrency < 5; concurrency++) {
            final LinkedBlockingQueue queue = new LinkedBlockingQueue(Integer.MAX_VALUE);
            long average = PerfTest_LinkedBlockingQueue_NonBlock.averageRun(warmupRuns, runs, queue, false, concurrency, REPETITIONS);
            System.out.format("PerfTest_MpmcTransferArrayQueue_NonBlock %,d (%dx%d)\n", average, concurrency, concurrency);
        }
    }
}
