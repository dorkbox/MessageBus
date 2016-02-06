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
package dorkbox.util.messagebus.subscription.asm;

import com.esotericsoftware.reflectasm.MethodAccess;

/**
 * Synchronizes message handler invocations for all handlers that specify @Synchronized
 *
 * @author bennidi
 *         Date: 3/31/13
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public
class AsmSynchronizedInvocation implements AsmInvocation {

    private final AsmInvocation delegate;

    public
    AsmSynchronizedInvocation(AsmInvocation delegate) {
        this.delegate = delegate;
    }

    @Override
    public
    void invoke(final Object listener, final MethodAccess handler, final int methodIndex, final Object message) throws Throwable {
        synchronized (listener) {
            this.delegate.invoke(listener, handler, methodIndex, message);
        }
    }

    @Override
    public
    void invoke(final Object listener, final MethodAccess handler, int methodIndex, final Object message1, final Object message2) throws Throwable {
        synchronized (listener) {
            this.delegate.invoke(listener, handler, methodIndex, message1, message2);
        }
    }

    @Override
    public
    void invoke(final Object listener, final MethodAccess handler, final int methodIndex, final Object message1, final Object message2, final Object message3) throws Throwable {
        synchronized (listener) {
            this.delegate.invoke(listener, handler, methodIndex, message1, message2, message3);
        }
    }
}
