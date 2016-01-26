package dorkbox.util.messagebus.perfTests;

public class LinkedBlockingQueue {
    public static final int REPETITIONS = 50 * 1000 * 100;

    private static final int bestRunsToAverage = 4;
    private static final int runs = 10;
    private static final int warmups = 3;

    public static void main(final String[] args) throws Exception {
        System.out.format("reps: %,d  %s\n", REPETITIONS, LinkedBlockingQueue.class.getSimpleName());

        for (int concurrency = 1; concurrency < 5; concurrency++) {
            final java.util.concurrent.LinkedBlockingQueue queue = new java.util.concurrent.LinkedBlockingQueue(1024);
            final Integer initialValue = Integer.valueOf(777);
            new LBQ_Block().run(REPETITIONS, concurrency, concurrency, warmups, runs, bestRunsToAverage, false, queue,
                                initialValue);
        }

        System.out.println("");
        System.out.println("");

        for (int concurrency = 1; concurrency < 5; concurrency++) {
            final java.util.concurrent.LinkedBlockingQueue queue = new java.util.concurrent.LinkedBlockingQueue(1024);
            final Integer initialValue = Integer.valueOf(777);
            new LBQ_NonBlock().run(REPETITIONS, concurrency, concurrency, warmups, runs, bestRunsToAverage, false, queue,
                                   initialValue);
        }
    }

    static
    class LBQ_Block extends Base_BlockingQueue<Integer> {}

    static
    class LBQ_NonBlock extends Base_Queue<Integer> {}
}
