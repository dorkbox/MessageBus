package net.engio.mbassy.multi.disruptor;

import java.util.Collection;

import net.engio.mbassy.multi.MultiMBassador;
import net.engio.mbassy.multi.subscription.Subscription;

import com.lmax.disruptor.WorkHandler;

/**
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class InvokeProcessor implements WorkHandler<MessageHolder> {
    private final MultiMBassador publisher;

    public InvokeProcessor(final MultiMBassador publisher) {
        this.publisher = publisher;
    }

    @Override
    public void onEvent(MessageHolder event) throws Exception {
//        switch (event.messageType) {
//            case ONE: {
                publish(event.subscriptions, event.message1);
                event.message1 = null; // cleanup
                event.subscriptions = null;
//                return;
//            }
//            case TWO: {
////                publisher.publish(this.message1, this.message2);
//                event.message1 = null; // cleanup
//                event.message2 = null; // cleanup
//                return;
//            }
//            case THREE: {
////                publisher.publish(this.message1, this.message2, this.message3);
//                event.message1 = null; // cleanup
//                event.message2 = null; // cleanup
//                event.message3 = null; // cleanup
//                return;
//            }
//            case ARRAY: {
////                publisher.publish(this.messages);
//                event.messages = null; // cleanup
//                return;
//            }
//        }
    }

    private void publish(Collection<Subscription> subscriptions, Object message) {
        Class<? extends Object> messageClass = message.getClass();

        if (messageClass.equals(DeadMessage.class)) {
            for (Subscription sub : subscriptions) {
                sub.publishToSubscription(this.publisher, message);
            }
        } else {
            Object[] vararg = null;

            for (Subscription sub : subscriptions) {
//                boolean handled = false;
//                if (sub.isVarArg()) {
//                    // messageClass will NEVER be an array to begin with, since that will call the multi-arg method
//                    if (vararg == null) {
//                        // messy, but the ONLY way to do it.
//                        vararg = (Object[]) Array.newInstance(messageClass, 1);
//                        vararg[0] = message;
//
//                        Object[] newInstance =  new Object[1];
//                        newInstance[0] = vararg;
//                        vararg = newInstance;
//                    }
//
//                    handled = true;
//                    sub.publishToSubscription(this.publisher, vararg);
//                }

//                if (!handled) {
                    sub.publishToSubscription(this.publisher, message);
//                }
            }
        }
    }
}