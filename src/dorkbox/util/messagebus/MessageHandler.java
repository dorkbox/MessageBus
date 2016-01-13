package dorkbox.util.messagebus;

import com.lmax.disruptor.LifecycleAware;
import com.lmax.disruptor.WorkHandler;
import dorkbox.util.messagebus.publication.Publisher;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public
class MessageHandler implements WorkHandler<MessageHolder>, LifecycleAware {
    private final Publisher publisher;

    AtomicBoolean shutdown = new AtomicBoolean(false);

    public
    MessageHandler(Publisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public
    void onEvent(final MessageHolder event) throws Exception {
        final int messageType = event.type;
        switch (messageType) {
            case MessageType.ONE: {
                Object message1 = event.message1;
//                System.err.println("(" + sequence + ")" + message1);
//                this.workProcessor.release(sequence);
                this.publisher.publish(message1);
                return;
            }
            case MessageType.TWO: {
                Object message1 = event.message1;
                Object message2 = event.message2;
                this.publisher.publish(message1, message2);
                return;
            }
            case MessageType.THREE: {
                Object message1 = event.message1;
                Object message2 = event.message2;
                Object message3 = event.message3;
                this.publisher.publish(message1, message2, message3);
                return;
            }
            case MessageType.ARRAY: {
                Object[] messages = event.messages;
                this.publisher.publish(messages);
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
