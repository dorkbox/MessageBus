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

import dorkbox.util.messagebus.common.simpleq.Node;
import dorkbox.util.messagebus.common.simpleq.jctools.MpmcArrayQueue;
import dorkbox.util.messagebus.common.simpleq.jctools.MpmcArrayTransferQueue;

public class MpmcQueueAltPerfTest {
    // 15 == 32 * 1024
    public static final int REPETITIONS = Integer.getInteger("reps", 50) * 1000 * 100;
    public static final Integer TEST_VALUE = Integer.valueOf(777);

    public static final int QUEUE_CAPACITY = 1 << Integer.getInteger("pow2.capacity", 17);

    public static void main(final String[] args) throws Exception {
        System.out.println(VMSupport.vmDetails());
        System.out.println(ClassLayout.parseClass(Node.class).toPrintable());

        System.out.println("capacity:" + QUEUE_CAPACITY + " reps:" + REPETITIONS);
        final MpmcArrayTransferQueue queue = new MpmcArrayTransferQueue(QUEUE_CAPACITY);

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

    private static long performanceRun(int runNumber, MpmcArrayTransferQueue queue) throws Exception {
        Producer p = new Producer(queue);
        Thread thread = new Thread(p);
        thread.start(); // producer will timestamp start

        MpmcArrayTransferQueue consumer = queue;
        Object result;
        int i = REPETITIONS;
        do {
            result = consumer.take(false, 0);
        } while (0 != --i);
        long end = System.nanoTime();

        thread.join();
        long duration = end - p.start;
        long ops = REPETITIONS * 1000L * 1000L * 1000L / duration;
        String qName = queue.getClass().getSimpleName();
        System.out.format("%d - ops/sec=%,d - %s result=%d\n", runNumber, ops, qName, ((Node)result).item1);
        return ops;
    }

    public static class Producer implements Runnable {
        private final MpmcArrayTransferQueue queue;
        long start;

        public Producer(MpmcArrayTransferQueue queue) {
            this.queue = queue;
        }

        @Override
        public void run() {
            MpmcArrayTransferQueue producer = this.queue;
            int i = REPETITIONS;
            long s = System.nanoTime();

            MpmcArrayQueue<Node> pool = new MpmcArrayQueue<Node>(2);
            pool.offer(new Node());
            pool.offer(new Node());

            Node node;
            do {
                node = pool.poll();
                node.item1 = TEST_VALUE;

                producer.put(node, false, 0);

                pool.offer(node);
            } while (0 != --i);
            this.start = s;
        }
    }
}
