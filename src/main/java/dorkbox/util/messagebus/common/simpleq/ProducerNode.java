package dorkbox.util.messagebus.common.simpleq;

import java.util.concurrent.atomic.AtomicReference;

import com.lmax.disruptor.MessageHolder;

/**
 * Nodes hold partially exchanged data. This class opportunistically subclasses AtomicReference to represent the hole. So get() returns
 * hole, and compareAndSet CAS'es value into hole. This class cannot be parameterized as "V" because of the use of non-V CANCEL sentinels.
 */
final class ProducerNode extends AtomicReference<MessageHolder> {
    private static final long serialVersionUID = 1L;


    /** The element offered by the Thread creating this node. */
    public volatile MessageHolder item = new MessageHolder();

    /** The Thread waiting to be signalled; null until waiting. */
    public volatile Thread waiter = null;

    // for the stack, for waiting producers
    public ProducerNode next = null;

    public ProducerNode() {
    }
}
