/*
 * Copyright 2012 Benjamin Diedrichsen
 *
 * Permission is hereby granted, free  of charge, to any person obtaining
 * a  copy  of this  software  and  associated  documentation files  (the
 * "Software"), to  deal in  the Software without  restriction, including
 * without limitation  the rights to  use, copy, modify,  merge, publish,
 * distribute,  sublicense, and/or sell  copies of  the Software,  and to
 * permit persons to whom the Software  is furnished to do so, subject to
 * the following conditions:
 *
 * The  above  copyright  notice  and  this permission  notice  shall  be
 * included in all copies or substantial portions of the Software.
 *
 * THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
 * EXPRESS OR  IMPLIED, INCLUDING  BUT NOT LIMITED  TO THE  WARRANTIES OF
 * MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE,  ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package dorkbox.util.messagebus.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Run various tests concurrently. A given instance of runnable will be used to spawn and start
 * as many threads as specified by an additional parameter or (if multiple runnables have been
 * passed to the method) one thread for each runnable.
 * <p/>
 * Date: 2/14/12
 *
 * @Author bennidi
 */
public class ConcurrentExecutor {


    public static void runConcurrent(final Runnable unit, int numberOfConcurrentExecutions) {
        Runnable[] units = new Runnable[numberOfConcurrentExecutions];
        // create the tasks and schedule for execution
        for (int i = 0; i < numberOfConcurrentExecutions; i++) {
            units[i] = unit;
        }
        runConcurrent(units);
    }


    public static void runConcurrent(int numberOfConcurrentExecutions, final Runnable... units) {
        Runnable[] runnables = new Runnable[numberOfConcurrentExecutions * units.length];
        // create the tasks and schedule for execution
        for (int i = 0; i < numberOfConcurrentExecutions; i++) {
            for(int k = 0; k < units.length; k++) {
                runnables[k * numberOfConcurrentExecutions +i] = units[k];
            }
        }
        runConcurrent(runnables);
    }


    public static void runConcurrent(final Runnable... units) {
        ExecutorService executor = Executors.newCachedThreadPool();
        List<Future<Long>> returnValues = new ArrayList<Future<Long>>();

        // create the tasks and schedule for execution
        for (final Runnable unit : units) {
            Callable<Long> wrapper = new Callable<Long>() {
                @Override
                public Long call() throws Exception {
                    long start = System.currentTimeMillis();
                    unit.run();
                    return System.currentTimeMillis() - start;
                }
            };
            returnValues.add(executor.submit(wrapper));
        }


        // wait until all tasks have been executed
        try {
            executor.shutdown();// tells the thread pool to execute all waiting tasks
            executor.awaitTermination(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            // unlikely that this will happen
            e.printStackTrace();
        }

        for(Future<?> task : returnValues){
            try {
                task.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }


}
