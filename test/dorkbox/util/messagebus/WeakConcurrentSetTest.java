/*
 * Copyright 2013 Benjamin Diedrichsen
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
package dorkbox.util.messagebus;

import dorkbox.util.messagebus.common.ConcurrentExecutor;
import dorkbox.util.messagebus.common.WeakConcurrentSet;
import org.junit.Test;

import java.util.Collection;
import java.util.HashSet;
import java.util.Random;

/**
 *
 *
 * @author bennidi
 *         Date: 3/29/13
 */
public class WeakConcurrentSetTest extends ConcurrentSetTest{





    @Override
    protected Collection createSet() {
        return new WeakConcurrentSet();
    }

    @Test
    public void testIteratorCleanup() {

        // Assemble
        final HashSet<Object> permanentElements = new HashSet<Object>();
        final Collection testSetWeak = createSet();
        final Random rand = new Random();

        for (int i = 0; i < this.numberOfElements; i++) {
            Object candidate = new Object();

            if (rand.nextInt() % 3 == 0) {
                permanentElements.add(candidate);
            }
            testSetWeak.add(candidate);
        }

        // Remove/Garbage collect all objects that have not
        // been inserted into the set of permanent candidates.
        runGC();

        ConcurrentExecutor.runConcurrent(new Runnable() {
            @Override
            public void run() {
                for (Object testObject : testSetWeak) {
                    // do nothing
                    // just iterate to trigger automatic clean up
                    System.currentTimeMillis();
                }
            }
        }, this.numberOfThreads);

        // the set should have cleaned up the garbage collected elements
        // it must still contain all of the permanent objects
        // since different GC mechanisms can be used (not necessarily full, stop-the-world) not all dead objects
        // must have been collected
        assertTrue(permanentElements.size() <= testSetWeak.size() && testSetWeak.size() < this.numberOfElements);
        for (Object test : testSetWeak) {
            assertTrue(permanentElements.contains(test));
        }
    }


}
