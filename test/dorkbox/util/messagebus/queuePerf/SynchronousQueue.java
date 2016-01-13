package dorkbox.util.messagebus.queuePerf;

public class SynchronousQueue {
    public static final int REPETITIONS = 50 * 1000 * 100;

    private static final int bestRunsToAverage = 4;
    private static final int runs = 10;
    private static final int warmups = 3;

    public static void main(final String[] args) throws Exception {
        System.out.format("reps: %,d  %s\n", REPETITIONS, SynchronousQueue.class.getSimpleName());

        for (int concurrency = 1; concurrency < 5; concurrency++) {
            final java.util.concurrent.SynchronousQueue queue = new java.util.concurrent.SynchronousQueue();
            final Integer initialValue = Integer.valueOf(777);
            new SQ_Block().run(REPETITIONS, concurrency, concurrency, warmups, runs, bestRunsToAverage, false, queue,
                                initialValue);
        }

        System.out.println("");
        System.out.println("");

        for (int concurrency = 1; concurrency < 5; concurrency++) {
            final java.util.concurrent.SynchronousQueue queue = new java.util.concurrent.SynchronousQueue();
            final Integer initialValue = Integer.valueOf(777);
            new SQ_NonBlock().run(REPETITIONS, concurrency, concurrency, warmups, runs, bestRunsToAverage, false, queue,
                                   initialValue);
        }
    }

    static
    class SQ_Block extends Base_BlockingQueue<Integer> {}

    static
    class SQ_NonBlock extends Base_Queue<Integer> {}
}
