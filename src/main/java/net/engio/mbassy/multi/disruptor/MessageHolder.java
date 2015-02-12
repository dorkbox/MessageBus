package net.engio.mbassy.multi.disruptor;

import java.util.Collection;

import net.engio.mbassy.multi.MultiMBassador;
import net.engio.mbassy.multi.subscription.Subscription;

/**
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class MessageHolder {
    public MessageType messageType = MessageType.ONE;

    public Object message1 = null;
    public Object message2 = null;
    public Object message3 = null;
    public Object[] messages = null;

    public Collection<Subscription> subscriptions;

    public MessageHolder() {
    }

    public void publish(MultiMBassador publisher) {
//
//        switch (this.messageType) {
//            case ONE: {
//                publisher.publishExecutor(this.message1);
//                this.message1 = null; // cleanup
//                return;
//            }
//            case TWO: {
//                publisher.publish(this.message1, this.message2);
//                this.message1 = null; // cleanup
//                this.message2 = null; // cleanup
//                return;
//            }
//            case THREE: {
//                publisher.publish(this.message1, this.message2, this.message3);
//                this.message1 = null; // cleanup
//                this.message2 = null; // cleanup
//                this.message3 = null; // cleanup
//                return;
//            }
//            case ARRAY: {
//                publisher.publish(this.messages);
//                this.messages = null; // cleanup
//                return;
//            }
//        }
    }
}