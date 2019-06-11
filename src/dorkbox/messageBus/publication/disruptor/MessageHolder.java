/*
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
package dorkbox.messageBus.publication.disruptor;

import java.lang.reflect.Method;

import com.esotericsoftware.reflectasm.MethodAccess;

import dorkbox.messageBus.error.ErrorHandler;
import dorkbox.messageBus.subscription.asm.AsmInvocation;
import dorkbox.messageBus.subscription.reflection.ReflectionInvocation;

/**
 * @author dorkbox, llc Date: 2/2/15
 */
public
class MessageHolder {
    public int type = MessageType.ASM_ONE;

    public Object message1 = null;
    public Object message2 = null;
    public Object message3 = null;

    public ErrorHandler errorHandler = null;

    public AsmInvocation asmInvocation = null;
    public ReflectionInvocation reflectionInvocation = null;
    public Object listener = null;
    public Method method = null;
    public MethodAccess handler = null;
    public int handleIndex = 0;

    public
    MessageHolder() {}

    /**
     * Make sure objects do not live longer than they are supposed to
     */
    public
    void clear() {
        type = MessageType.ASM_ONE;

        message1 = null;
        message2 = null;
        message3 = null;

        errorHandler = null;
        asmInvocation = null;
        reflectionInvocation = null;
        listener = null;
        method = null;

        handler = null;
        handleIndex = 0;
    }
}
