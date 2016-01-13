package dorkbox.util.messagebus.queuePerf;

@SuppressWarnings("Duplicates")
public class MpmcArrayQueue extends Base_BlockingQueue {

    public static final int REPETITIONS = 50 * 1000 * 100;

    private static final int bestRunsToAverage = 4;
    private static final int runs = 10;
    private static final int warmups = 3;

    public static void main(final String[] args) throws Exception {
        System.out.format("reps: %,d  %s: \n", REPETITIONS, MpmcArrayQueue.class.getSimpleName());

        for (int concurrency = 1; concurrency < 5; concurrency++) {
            final org.jctools.queues.MpmcArrayQueue queue = new org.jctools.queues.MpmcArrayQueue(1 << 17);
            final Integer initialValue = Integer.valueOf(777);
            new MpmcArray_NonBlock().run(REPETITIONS, concurrency, concurrency, warmups, runs, bestRunsToAverage, false, queue,
                                         initialValue);
        }
    }

    static
    class MpmcArray_NonBlock extends Base_Queue<Integer> {}
}
