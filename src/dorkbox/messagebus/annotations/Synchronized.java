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
package dorkbox.messagebus.annotations;

import java.lang.annotation.*;

/**
 * A handler marked with this annotation is guaranteed to be invoked in a thread-safe manner, that is, no
 * other running message publication will be able to invoke this or any other synchronized handler of the same
 * listener until the handler completed. It is equal to wrapping the handler code in a synchronized{} block.
 * This feature will reduce performance of message publication. Try to avoid shared mutable state whenever possible
 * and use immutable data instead.
 * <p/>
 * Note: Unsynchronized handlers may still be invoked concurrently with synchronized ones
 *
 * @author bennidi
 *         Date: 3/31/13
 */
@Retention(value = RetentionPolicy.RUNTIME)
@Inherited
@Target(value = {ElementType.METHOD, ElementType.ANNOTATION_TYPE})
public
@interface Synchronized {}
