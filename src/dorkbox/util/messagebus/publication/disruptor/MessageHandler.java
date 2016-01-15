package dorkbox.util.messagebus.publication.disruptor;

import com.lmax.disruptor.LifecycleAware;
import com.lmax.disruptor.WorkHandler;
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
            case MessageType.ARRAY: {
                Object[] messages = event.messages;
                this.publisher.publish(syncPublication, messages);
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
