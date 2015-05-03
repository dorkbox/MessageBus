/*
 * Copyright 2012 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package dorkbox.util.messagebus;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.util.VMSupport;

import dorkbox.util.messagebus.common.simpleq.Node;
import dorkbox.util.messagebus.common.simpleq.jctools.SimpleQueue;

public class SimpleQueueAltPerfTest {
    // 15 == 32 * 1024
    public static final int REPETITIONS = Integer.getInteger("reps", 50) * 1000 * 10;
    public static final Integer TEST_VALUE = Integer.valueOf(777);

    public static final int QUEUE_CAPACITY = 1 << Integer.getInteger("pow2.capacity", 17);

    private static final int concurrency = 8;

    public static void main(final String[] args) throws Exception {
        System.out.println(VMSupport.vmDetails());
        System.out.println(ClassLayout.parseClass(Node.class).toPrintable());

        System.out.println("capacity:" + QUEUE_CAPACITY + " reps:" + REPETITIONS + "  Concurrency " + concurrency);
        final SimpleQueue queue = new SimpleQueue(QUEUE_CAPACITY);

        final long[] results = new long[20];
        for (int i = 0; i < 20; i++) {
            System.gc();
            results[i] = performanceRun(i, queue);
        }
        // only average last 10 results for summary
        long sum = 0;
        for (int i = 10; i < 20; i++) {
            sum += results[i];
        }
        System.out.format("summary,QueuePerfTest,%s %,d\n", queue.getClass().getSimpleName(), sum / 10);
    }

    private static long performanceRun(int runNumber, SimpleQueue queue) throws Exception {

        Producer[] producers = new Producer[concurrency];
        Consumer[] consumers = new Consumer[concurrency];
        Thread[] threads = new Thread[concurrency*2];

        for (int i=0;i<concurrency;i++) {
            producers[i] = new Producer(queue);
            consumers[i] = new Consumer(queue);
        }

        for (int j=0,i=0;i<concurrency;i++,j+=2) {
            threads[j] = new Thread(producers[i], "Producer " + i);
            threads[j+1] = new Thread(consumers[i], "Consumer " + i);
        }

        for (int i=0;i<concurrency*2;i+=2) {
            threads[i].start();
            threads[i+1].start();
        }

        for (int i=0;i<concurrency*2;i+=2) {
            threads[i].join();
            threads[i+1].join();
        }

        long start = Long.MAX_VALUE;
        long end = -1;

        for (int i=0;i<concurrency;i++) {
            if (producers[i].start < start) {
                start = producers[i].start;
            }

            if (consumers[i].end > end) {
                end = consumers[i].end;
            }
        }


        long duration = end - start;
        long ops = REPETITIONS * 1_000_000_000L / duration;
        String qName = queue.getClass().getSimpleName();

        System.out.format("%d - ops/sec=%,d - %s\n", runNumber, ops, qName);
        return ops;
    }

    public static class Producer implements Runnable {
        private final SimpleQueue queue;
        volatile long start;

        public Producer(SimpleQueue queue) {
            this.queue = queue;
        }

        @Override
        public void run() {
            SimpleQueue producer = this.queue;
            int i = REPETITIONS;
            this.start = System.nanoTime();

            try {
                do {
                    producer.put(TEST_VALUE);
                } while (0 != --i);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public static class Consumer implements Runnable {
        private final SimpleQueue queue;
        Object result;
        volatile long end;

        public Consumer(SimpleQueue queue) {
            this.queue = queue;
        }

        @Override
        public void run() {
            SimpleQueue consumer = this.queue;
            Object result = null;
            int i = REPETITIONS;

            try {
                do {
                    result = consumer.take();
                } while (0 != --i);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            this.result = result;
            this.end = System.nanoTime();
        }
    }
}
