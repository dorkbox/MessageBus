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
import dorkbox.messageBus.error.PublicationError;
import dorkbox.messageBus.subscription.asm.AsmInvocation;
import dorkbox.messageBus.subscription.reflection.ReflectionInvocation;

public
class DirectInvocation implements Publisher {

    public
    DirectInvocation() {
    }

    // ASM
    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final AsmInvocation invocation, final Object listener, final MethodAccess handler, final int handleIndex,
                 final Object message) {
        try {
            invocation.invoke(listener, handler, handleIndex, message);
        } catch (Throwable e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during publication of message.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message));
        }
    }

    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final AsmInvocation invocation, final Object listener, final MethodAccess handler, final int handleIndex,
                 final Object message1,
                 final Object message2) {

        try {
            invocation.invoke(listener, handler, handleIndex, message1, message2);
        } catch (Throwable e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during publication of message.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message1, message2));
        }

    }

    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final AsmInvocation invocation, final Object listener, final MethodAccess handler, final int handleIndex,
                 final Object message1, final Object message2, final Object message3) {


        try {
            invocation.invoke(listener, handler, handleIndex, message1, message2, message3);
        } catch (Throwable e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during publication of message.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message1, message2, message3));
        }
    }



    // REFLECTION
    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final ReflectionInvocation invocation, final Object listener, final Method method,
                 final Object message) {

        try {
            invocation.invoke(listener, method, message);
        } catch (Throwable e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during publication of message.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message));
        }
    }

    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final ReflectionInvocation invocation, final Object listener, final Method method,
                 final Object message1, final Object message2) {

        try {
            invocation.invoke(listener, method, message1, message2);
        } catch (Throwable e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during publication of message.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message1, message2));
        }
    }

    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final ReflectionInvocation invocation, final Object listener, final Method method,
                 final Object message1, final Object message2, final Object message3) {

        try {
            invocation.invoke(listener, method, message1, message2, message3);
        } catch (Throwable e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during publication of message.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message1, message2, message3));
        }
    }


    public
    boolean hasPendingMessages() {
        return false;
    }

    @Override
    public
    void shutdown() {
    }
}
