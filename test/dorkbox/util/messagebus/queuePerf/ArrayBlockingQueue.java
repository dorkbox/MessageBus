package dorkbox.util.messagebus.queuePerf;

@SuppressWarnings("Duplicates")
public class ArrayBlockingQueue extends Base_BlockingQueue {

    public static final int REPETITIONS = 50 * 1000 * 100;

    private static final int bestRunsToAverage = 4;
    private static final int runs = 10;
    private static final int warmups = 3;

    public static void main(final String[] args) throws Exception {
        System.out.format("reps: %,d  %s: \n", REPETITIONS, ArrayBlockingQueue.class.getSimpleName());

        for (int concurrency = 1; concurrency < 5; concurrency++) {
            final java.util.concurrent.ArrayBlockingQueue queue = new java.util.concurrent.ArrayBlockingQueue(1024);
            final Integer initialValue = Integer.valueOf(777);
            new ABQ_Block().run(REPETITIONS, concurrency, concurrency, warmups, runs, bestRunsToAverage, false, queue,
                                         initialValue);
        }

//        System.out.println("");
//        System.out.println("");
//
//        for (int concurrency = 1; concurrency < 5; concurrency++) {
//            final java.util.concurrent.ArrayBlockingQueue queue = new java.util.concurrent.ArrayBlockingQueue(1024);
//            final Integer initialValue = Integer.valueOf(777);
//            new ABQ_NonBlock().run(REPETITIONS, concurrency, concurrency, warmups, runs, bestRunsToAverage, false, queue,
//                                         initialValue);
//        }
    }

    static
    class ABQ_Block extends Base_BlockingQueue<Integer> {}

    static
    class ABQ_NonBlock extends Base_Queue<Integer> {}
}
