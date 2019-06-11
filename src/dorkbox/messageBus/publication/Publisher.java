/*
 * Copyright 2019 dorkbox, llc
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
package dorkbox.messageBus.publication;

import java.lang.reflect.Method;

import com.esotericsoftware.reflectasm.MethodAccess;

import dorkbox.messageBus.error.ErrorHandler;
import dorkbox.messageBus.subscription.asm.AsmInvocation;
import dorkbox.messageBus.subscription.reflection.ReflectionInvocation;

public
interface Publisher {
    // ASM
    void publish(final ErrorHandler errorHandler,
                 final AsmInvocation invocation, final Object listener, final MethodAccess handler, final int handleIndex,
                 final Object message);

    void publish(final ErrorHandler errorHandler,
                 final AsmInvocation invocation, final Object listener, final MethodAccess handler, final int handleIndex,
                 final Object message1,
                 final Object message2);

    void publish(final ErrorHandler errorHandler,
                 final AsmInvocation invocation, final Object listener, final MethodAccess handler, final int handleIndex,
                 final Object message1, final Object message2, final Object message3);

    // REFLECTION
    void publish(final ErrorHandler errorHandler,
                 final ReflectionInvocation invocation, final Object listener, final Method method,
                 final Object message);

    void publish(final ErrorHandler errorHandler,
                 final ReflectionInvocation invocation, final Object listener, final Method method,
                 final Object message1, final Object message2);

    void publish(final ErrorHandler errorHandler,
                 final ReflectionInvocation invocation, final Object listener, final Method method,
                 final Object message1, final Object message2, final Object message3);


    boolean hasPendingMessages();
    void shutdown();
}
