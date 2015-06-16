package dorkbox.util.messagebus.queuePerf;

import dorkbox.util.messagebus.common.simpleq.MpmcMultiTransferArrayQueue;

public class PerfTest_MpmcTransferArrayQueue {
    public static final int REPETITIONS = 50 * 1000 * 100;


    public static void main(final String[] args) throws Exception {
        final int repetitions = 50_000_00;

        final int warmupRuns = 4;
        final int runs = 3;

        for (int concurrency = 1; concurrency < 5; concurrency++) {
            final MpmcMultiTransferArrayQueue queue = new MpmcMultiTransferArrayQueue(concurrency);
            long average = PerfTest_MpmcTransferArrayQueue_Block.averageRun(warmupRuns, runs, queue, false, concurrency, repetitions);
            System.out.format("PerfTest_MpmcTransferArrayQueue_Block %,d (%dx%d)\n", average, concurrency, concurrency);
        }

        for (int concurrency = 1; concurrency < 5; concurrency++) {
            final MpmcMultiTransferArrayQueue queue = new MpmcMultiTransferArrayQueue(concurrency);
            long average = PerfTest_MpmcTransferArrayQueue_NonBlock.averageRun(warmupRuns, runs, queue, false, concurrency, repetitions);
            System.out.format("PerfTest_MpmcTransferArrayQueue_NonBlock %,d (%dx%d)\n", average, concurrency, concurrency);
        }
    }
}
