package com.lmax.disruptor;

import dorkbox.util.messagebus.PubSubSupport;

/**
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class EventProcessor2 implements WorkHandlerEarlyRelease<MessageHolder> {
    private final PubSubSupport publisher;
    private WaitingWorkProcessor workProcessor;

    public EventProcessor2(PubSubSupport publisher) {
        this.publisher = publisher;
    }

    @Override
    public void setProcessor(WaitingWorkProcessor workProcessor) {
//        this.workProcessor = workProcessor;
    }

    @Override
    public void onEvent(long sequence, MessageHolder event) throws Exception {
        MessageType messageType = event.messageType;
        switch (messageType) {
            case ONE: {
                Object message1 = event.message1;
//                System.err.println("(" + sequence + ")" + message1);
//                this.workProcessor.release(sequence);
                this.publisher.publish(message1);
                return;
            }
            case TWO: {
                Object message1 = event.message1;
                Object message2 = event.message2;
                this.publisher.publish(message1, message2);
                return;
            }
            case THREE: {
                Object message1 = event.message1;
                Object message2 = event.message2;
                Object message3 = event.message3;
                this.publisher.publish(message1, message2, message3);
                return;
            }
            case ARRAY: {
                Object[] messages = event.messages;
                this.publisher.publish(messages);
                return;
            }
        }
    }
}