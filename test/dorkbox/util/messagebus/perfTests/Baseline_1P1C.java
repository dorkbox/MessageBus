package dorkbox.util.messagebus.perfTests;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

@SuppressWarnings("Duplicates")
public
class Baseline_1P1C<T> {
    public static final int REPETITIONS = 50 * 1000 * 100;

    private static final int bestRunsToAverage = 4;
    private static final int runs = 10;
    private static final int warmups = 43;

    public static void main(final String[] args) throws Exception {
        System.out.format("reps: %,d  %s: \n", REPETITIONS, Baseline_1P1C.class.getSimpleName());

        final java.util.concurrent.ConcurrentLinkedQueue queue = new java.util.concurrent.ConcurrentLinkedQueue();
        final Integer initialValue = Integer.valueOf(777);
        new Baseline_1P1C().run(REPETITIONS, 1, 1, warmups, runs, bestRunsToAverage, false, queue,
                               initialValue);
    }


    public
    void run(final int repetitions,
             final int producersCount,
             final int consumersCount,
             final int warmups,
             final int runs,
             final int bestRunsToAverage,
             final boolean showStats,
             final Queue<T> queue,
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
                          producersCount,
                          consumersCount, average);
    }


    /**
     * Benchmarks how long it takes to push X number of items total. If there are is 1P and 1C, then X items will be sent from a producer to
     * a consumer. Of there are NP and NC threads, then X/N (for a total of X) items will be sent.
     */
    private
    long performanceRun(final int runNumber,
                        final Queue<T> queue,
                        final boolean showStats,
                        final int producersCount,
                        final int consumersCount,
                        int repetitions,
                        T initialValue) throws Exception {

        // this just measure how long it takes to count from 0 - repetitions
        long start = System.nanoTime();
        long end = -1;

        for (int i=0;i<repetitions;i++) {
            end += 1;
        }

        end = System.nanoTime();

        long duration = end - start;
        long ops = repetitions * 1000000000L / duration;

        if (showStats) {
            System.out.format("%d - ops/sec=%,d\n", runNumber, ops);
        }
        return ops;
    }
}
