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

import dorkbox.util.messagebus.common.simpleq.HandlerFactory;
import dorkbox.util.messagebus.common.simpleq.MpmcExchangerQueueAlt;
import dorkbox.util.messagebus.common.simpleq.Node;

public class MpmcQueueAltPerfTest {
    // 15 == 32 * 1024
    public static final int REPETITIONS = Integer.getInteger("reps", 50) * 1000 * 1000;
    public static final Integer TEST_VALUE = Integer.valueOf(777);

    public static final int QUEUE_CAPACITY = 1 << Integer.getInteger("pow2.capacity", 17);

    public static void main(final String[] args) throws Exception {
        System.out.println("capacity:" + QUEUE_CAPACITY + " reps:" + REPETITIONS);

        HandlerFactory<Integer> factory = new HandlerFactory<Integer>() {
            @Override
            public Integer newInstance() {
                return Integer.valueOf(777);
            }
        };

        final MpmcExchangerQueueAlt<Integer> queue = new MpmcExchangerQueueAlt<Integer>(factory, QUEUE_CAPACITY);

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


    private static long performanceRun(int runNumber, MpmcExchangerQueueAlt<Integer> queue) throws Exception {
//        for (int i=0;i<CONCURRENCY_LEVEL;i++) {
            Producer p = new Producer(queue);
            Thread thread = new Thread(p);
            thread.start(); // producer will timestamp start
//        }

        MpmcExchangerQueueAlt<Integer> consumer = queue;
        Node<Integer> result;
        int i = REPETITIONS;
        int queueEmpty = 0;
        do {
            result = consumer.take();
        } while (0 != --i);
        long end = System.nanoTime();

        thread.join();
        long duration = end - p.start;
        long ops = REPETITIONS * 1000L * 1000L * 1000L / duration;
        String qName = queue.getClass().getSimpleName();
        Integer finalMessage = result.item;
        System.out.format("%d - ops/sec=%,d - %s result=%d failed.poll=%d failed.offer=%d\n", runNumber, ops,
                qName, finalMessage, queueEmpty, p.queueFull);
        return ops;
    }

    public static class Producer implements Runnable {
        private final MpmcExchangerQueueAlt<Integer> queue;
        int queueFull = 0;
        long start;

        public Producer(MpmcExchangerQueueAlt<Integer> queue) {
            this.queue = queue;
        }

        @Override
        public void run() {
            MpmcExchangerQueueAlt<Integer> producer = this.queue;
            int i = REPETITIONS;
            int f = 0;
            long s = System.nanoTime();
            Node<Integer> result;
            do {
                producer.put();
            } while (0 != --i);
            this.queueFull = f;
            this.start = s;
        }
    }
}
