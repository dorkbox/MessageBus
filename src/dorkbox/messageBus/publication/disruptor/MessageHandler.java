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

import java.util.concurrent.atomic.AtomicBoolean;

import com.lmax.disruptor.LifecycleAware;
import com.lmax.disruptor.WorkHandler;

import dorkbox.messageBus.publication.Publisher;

/**
 * @author dorkbox, llc Date: 2/2/15
 */
public
class MessageHandler implements WorkHandler<MessageHolder>, LifecycleAware {

    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final Publisher syncPublisher;

    public
    MessageHandler(final Publisher syncPublisher) {
        this.syncPublisher = syncPublisher;
    }

    @Override
    public
    void onEvent(final MessageHolder event) throws Exception {
        switch (event.type) {
            // ASM INVOCATION
            case MessageType.ASM_ONE:
                // this is by far the most common case.
                syncPublisher.publish(event.errorHandler, event.asmInvocation, event.listener, event.handler, event.handleIndex, event.message1);
                event.clear();
                return;
            case MessageType.ASM_TWO:
                syncPublisher.publish(event.errorHandler, event.asmInvocation, event.listener, event.handler, event.handleIndex, event.message1, event.message2);
                event.clear();
                return;
            case MessageType.ASM_THREE:
                syncPublisher.publish(event.errorHandler, event.asmInvocation, event.listener, event.handler, event.handleIndex, event.message1, event.message2, event.message3);
                event.clear();
                return;

            // REFLECT INVOCATION
            case MessageType.REFLECT_ONE:
                syncPublisher.publish(event.errorHandler, event.reflectionInvocation, event.listener, event.method, event.message1);
                event.clear();
                return;
            case MessageType.REFLECT_TWO:
                syncPublisher.publish(event.errorHandler, event.reflectionInvocation, event.listener, event.method, event.message1, event.message2);
                event.clear();
                return;
            case MessageType.REFLECT_THREE:
                syncPublisher.publish(event.errorHandler, event.reflectionInvocation, event.listener, event.method, event.message1, event.message2, event.message3);
                event.clear();
                //noinspection UnnecessaryReturnStatement
                return;
        }
    }

    @Override
    public
    void onStart() {
    }

    @Override
    public synchronized
    void onShutdown() {
        shutdown.set(true);
    }

    public
    boolean isShutdown() {
        return shutdown.get();
    }
}
