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
package dorkbox.util.messagebus.synchrony.disruptor;

import com.lmax.disruptor.LifecycleAware;
import com.lmax.disruptor.WorkHandler;
import dorkbox.util.messagebus.synchrony.MessageHolder;
import dorkbox.util.messagebus.synchrony.Synchrony;
import dorkbox.util.messagebus.publication.Publisher;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public
class MessageHandler implements WorkHandler<MessageHolder>, LifecycleAware {
    private final Publisher publisher;
    private final Synchrony syncPublication;

    AtomicBoolean shutdown = new AtomicBoolean(false);


    public
    MessageHandler(final Publisher publisher, final Synchrony syncPublication) {
        this.publisher = publisher;
        this.syncPublication = syncPublication;
    }

    @Override
    public
    void onEvent(final MessageHolder event) throws Exception {
        final int messageType = event.type;
        switch (messageType) {
            case MessageType.ONE: {
                this.publisher.publish(syncPublication, event.message1);
                return;
            }
            case MessageType.TWO: {
                Object message1 = event.message1;
                Object message2 = event.message2;
                this.publisher.publish(syncPublication, message1, message2);
                return;
            }
            case MessageType.THREE: {
                Object message1 = event.message1;
                Object message2 = event.message2;
                Object message3 = event.message3;
                this.publisher.publish(syncPublication, message3, message1, message2);
                return;
            }
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
