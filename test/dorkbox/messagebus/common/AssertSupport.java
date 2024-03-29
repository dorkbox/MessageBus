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
package dorkbox.messagebus.common;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;

/**
 * @author bennidi
 */
public class AssertSupport {

    private Runtime runtime = Runtime.getRuntime();
    protected Logger logger = LoggerFactory.getLogger(getClass().getSimpleName());
    private volatile long testExecutionStart;

    @Rule
    public TestName name = new TestName();


    @Before
    public void beforeTest(){
        this.logger.info("Running test " + getTestName());
        this.testExecutionStart = System.currentTimeMillis();
    }

    @After
    public void afterTest(){
        this.logger.info(String.format("Finished " + getTestName() + ": " + (System.currentTimeMillis() - this.testExecutionStart) + " ms"));
    }


    public void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void pause() {
        pause(10);
    }

    public String getTestName(){
        return getClass().getSimpleName() + "." + this.name.getMethodName();
    }

    public void runGC() {
        WeakReference ref = new WeakReference<Object>(new Object());
        while(ref.get() != null) {
            this.runtime.gc();
            pause();
        }
    }

    public void fail(String message) {
        Assert.fail(message);
    }

    public static
    void fail() {
        Assert.fail();
    }

    public void assertTrue(Boolean condition) {
        Assert.assertTrue(condition);
    }

    public void assertTrue(String message, Boolean condition) {
        Assert.assertTrue(message, condition);
    }

    public void assertFalse(Boolean condition) {
        Assert.assertFalse(condition);
    }

    public void assertNull(Object object) {
        Assert.assertNull(object);
    }

    public void assertNotNull(Object object) {
        Assert.assertNotNull(object);
    }

    public void assertFalse(String message, Boolean condition) {
        Assert.assertFalse(message, condition);
    }

    public void assertEquals(Object expected, Object actual) {
        Assert.assertEquals(expected, actual);
    }
}
