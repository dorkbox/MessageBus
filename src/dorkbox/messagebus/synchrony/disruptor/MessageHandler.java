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
package dorkbox.messagebus.synchrony.disruptor;

import com.lmax.disruptor.LifecycleAware;
import com.lmax.disruptor.WorkHandler;
import dorkbox.messagebus.error.ErrorHandlingSupport;
import dorkbox.messagebus.error.PublicationError;
import dorkbox.messagebus.subscription.Subscription;
import dorkbox.messagebus.synchrony.MessageHolder;
import dorkbox.messagebus.synchrony.Synchrony;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author dorkbox, llc Date: 2/2/15
 */
public
class MessageHandler implements WorkHandler<MessageHolder>, LifecycleAware {

    private final Synchrony syncPublication;
    private final ErrorHandlingSupport errorHandler;

    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public
    MessageHandler(final Synchrony syncPublication, final ErrorHandlingSupport errorHandler) {
        this.syncPublication = syncPublication;
        this.errorHandler = errorHandler;
    }

    @SuppressWarnings("Duplicates")
    @Override
    public
    void onEvent(final MessageHolder event) throws Exception {
        final int messageType = event.type;
        final Subscription[] subscriptions = event.subscriptions;

        try {
            switch (messageType) {
                case MessageType.ONE: {
                    syncPublication.publish(subscriptions, event.message1);
                    return;
                }
                case MessageType.TWO: {
                    syncPublication.publish(subscriptions, event.message1, event.message2);
                    return;
                }
                case MessageType.THREE: {
                    syncPublication.publish(subscriptions, event.message1, event.message2, event.message3);
                    //noinspection UnnecessaryReturnStatement
                    return;
                }
            }
        } catch (Throwable e) {
            switch (messageType) {
                case MessageType.ONE: {
                    errorHandler.handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.")
                                                                              .setCause(e)
                                                                              .setPublishedObject(event.message1));
                    return;
                }
                case MessageType.TWO: {
                    errorHandler.handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.")
                                                                              .setCause(e)
                                                                              .setPublishedObject(event.message1, event.message2));
                    return;
                }
                case MessageType.THREE: {
                    errorHandler.handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.")
                                                                              .setCause(e)
                                                                              .setPublishedObject(event.message1,
                                                                                                  event.message2,
                                                                                                  event.message3));
                    //noinspection UnnecessaryReturnStatement
                    return;
                }
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
