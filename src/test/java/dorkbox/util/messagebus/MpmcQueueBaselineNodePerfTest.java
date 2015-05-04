/*
 * Copyright 2012 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.util.messagebus;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.util.VMSupport;

import dorkbox.util.messagebus.common.simpleq.jctools.MpmcArrayQueue;
import dorkbox.util.messagebus.common.simpleq.jctools.Node;

public class MpmcQueueBaselineNodePerfTest {
    // 15 == 32 * 1024
    public static final int REPETITIONS = Integer.getInteger("reps", 50) * 1000 * 1000;
    public static final Integer TEST_VALUE = Integer.valueOf(777);

    public static final int QUEUE_CAPACITY = 1 << Integer.getInteger("pow2.capacity", 17);

    public static void main(final String[] args) throws Exception {
        System.out.println(VMSupport.vmDetails());
        System.out.println(ClassLayout.parseClass(Node.class).toPrintable());

        System.out.println("capacity:" + QUEUE_CAPACITY + " reps:" + REPETITIONS);
        final MpmcArrayQueue<Node> queue = new MpmcArrayQueue<Node>(QUEUE_CAPACITY);

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
        System.out.format("summary,QueuePerfTest,%s,%d\n", queue.getClass().getSimpleName(), sum / 10);
    }

    private static long performanceRun(int runNumber, MpmcArrayQueue<Node> queue) throws Exception {
        Producer p = new Producer(queue);
        Thread thread = new Thread(p);
        thread.start(); // producer will timestamp start

        MpmcArrayQueue<Node> consumer = queue;
        Node result;
        int i = REPETITIONS;
        int queueEmpty = 0;
        do {
            while (null == (result = consumer.poll())) {
                queueEmpty++;
                Thread.yield();
            }
        } while (0 != --i);
        long end = System.nanoTime();

        thread.join();
        long duration = end - p.start;
        long ops = REPETITIONS * 1000L * 1000L * 1000L / duration;
        String qName = queue.getClass().getSimpleName();
        System.out.format("%d - ops/sec=%,d - %s result=%d failed.poll=%d failed.offer=%d\n", runNumber, ops,
                qName, result.item1, queueEmpty, p.queueFull);
        return ops;
    }

    @SuppressWarnings("rawtypes")
    public static class Producer implements Runnable {
        private final MpmcArrayQueue queue;
        int queueFull = 0;
        long start;

        public Producer(MpmcArrayQueue queue) {
            this.queue = queue;
        }

        @Override
        public void run() {
            MpmcArrayQueue producer = this.queue;
            int i = REPETITIONS;
            int f = 0;
            long s = System.nanoTime();
            Node node = new Node();
            node.item1 = TEST_VALUE;
            do {
                while (!producer.offer(node)) {
                    Thread.yield();
                    f++;
                }
            } while (0 != --i);
            this.queueFull = f;
            this.start = s;
        }
    }
}
