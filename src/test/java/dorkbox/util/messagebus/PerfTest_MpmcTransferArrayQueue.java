package dorkbox.util.messagebus;

import dorkbox.util.messagebus.common.simpleq.MpmcTransferArrayQueue;

public class PerfTest_MpmcTransferArrayQueue {
    public static final int REPETITIONS = 50 * 1000 * 100;
    public static final Integer TEST_VALUE = Integer.valueOf(777);


    public static void main(final String[] args) throws Exception {
        System.out.println("reps:" + REPETITIONS);

        final int warmupRuns = 2;
        final int runs = 3;

        for (int concurrency = 1; concurrency < 5; concurrency++) {
            final MpmcTransferArrayQueue queue = new MpmcTransferArrayQueue(concurrency);
            long average = PerfTest_MpmcTransferArrayQueue_Block.averageRun(warmupRuns, runs, queue, false, concurrency, REPETITIONS);
            System.out.format("PerfTest_MpmcTransferArrayQueue_Block %,d (%dx%d)\n", average, concurrency, concurrency);
        }

        for (int concurrency = 1; concurrency < 5; concurrency++) {
            final MpmcTransferArrayQueue queue = new MpmcTransferArrayQueue(concurrency);
            long average = PerfTest_MpmcTransferArrayQueue_NonBlock.averageRun(warmupRuns, runs, queue, false, concurrency, REPETITIONS);
            System.out.format("PerfTest_MpmcTransferArrayQueue_NonBlock %,d (%dx%d)\n", average, concurrency, concurrency);
        }
    }
}
