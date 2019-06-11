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
 *
 *
 * Copyright 2015 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.messageBus.subscription.asm;

import com.esotericsoftware.reflectasm.MethodAccess;

/**
 * A handler invocation encapsulates the logic that is used to invoke a single
 * message handler to process a given message.
 *
 * A handler invocation might come in different flavours and can be composed
 * of various independent invocations by means of delegation (-> decorator pattern)
 *
 * If an exception is thrown during handler invocation it is wrapped and propagated
 * as a publication error
 *
 * @author bennidi
 *         Date: 11/23/12
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public
interface AsmInvocation {

    /**
     * Invoke the message delivery logic of this handler
     *
     * @param listener The listener that will receive the message. This can be a reference to a method object
     *                 from the java reflection api or any other wrapper that can be used to invoke the handler
     * @param message  The message to be delivered to the handler. This can be any object compatible with the object
     *                 type that the handler consumes
     * @param handler  The handler (method) that will be called via reflection
     */
    void invoke(Object listener, MethodAccess handler, int methodIndex, Object message) throws Throwable;

    /**
     * Invoke the message delivery logic of this handler
     *
     * @param listener The listener that will receive the message. This can be a reference to a method object
     *                 from the java reflection api or any other wrapper that can be used to invoke the handler
     * @param message1  The message to be delivered to the handler. This can be any object compatible with the object
     *                 type that the handler consumes
     * @param handler  The handler (method) that will be called via reflection
     */
    void invoke(Object listener, MethodAccess handler, int methodIndex, Object message1, Object message2) throws Throwable;

    /**
     * Invoke the message delivery logic of this handler
     *
     * @param listener The listener that will receive the message. This can be a reference to a method object
     *                 from the java reflection api or any other wrapper that can be used to invoke the handler
     * @param message1  The message to be delivered to the handler. This can be any object compatible with the object
     *                 type that the handler consumes
     * @param handler  The handler (method) that will be called via reflection
     */
    void invoke(Object listener, MethodAccess handler, int methodIndex, Object message1, Object message2, Object message3) throws Throwable;
}
