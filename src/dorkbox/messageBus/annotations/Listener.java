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
package dorkbox.messageBus.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation is meant to carry configuration that is shared among all instances of the annotated
 * listener. Supported configurations are:
 * <p/>
 * Reference type: The bus will use either strong or weak references to its registered listeners,
 * depending on which reference type (@see References) is set
 *
 * @author bennidi
 * @author dorkbox, llc
 *         Date: 2/6/16
 */
@Retention(value = RetentionPolicy.RUNTIME)
@Target(value = {ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Inherited
public
@interface Listener {
    /**
     * By default, references to message listeners (these are the objects/methods that receive messages) are strong. This default can be
     * changed via a static boolean (during startup, see MessageBus.useStrongReferencesByDefault).
     *
     * The benefits to use WEAK references, is to eliminate risks of memory leaks in managed environments (such as spring).
     *
     * the default here "Undefined" here so that the static boolean (MessageBus.useStrongReferencesByDefault) takes priority
     */
    References references() default References.Undefined;
}
