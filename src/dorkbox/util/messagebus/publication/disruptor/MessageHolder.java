package dorkbox.util.messagebus.publication.disruptor;

import dorkbox.util.messagebus.subscription.Subscription;

/**
 * @author dorkbox, llc Date: 2/2/15
 */
public
class MessageHolder {
    public int type = MessageType.ONE;
    public Subscription[] subscriptions;

    public Object message1 = null;
    public Object message2 = null;
    public Object message3 = null;
    public Object[] messages = null;

    public
    MessageHolder() {}
}
