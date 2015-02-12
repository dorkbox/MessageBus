package net.engio.mbassy.multi.disruptor;

/**
 * The dead message event is published whenever no message
 * handlers could be found for a given message publication.
 *
 * @author bennidi
 *         Date: 1/18/13
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
final public class DeadMessage {

    public Object[] relatedMessages = new Object[3];


    public DeadMessage(Object message) {
        this.relatedMessages[0] = message;
    }

    public DeadMessage(Object message1, Object message2) {
        this.relatedMessages[0] = message1;
        this.relatedMessages[1] = message2;
    }

    public DeadMessage(Object message1, Object message2, Object message3 ) {
        this.relatedMessages[0] = message1;
        this.relatedMessages[1] = message2;
        this.relatedMessages[2] = message3;
    }

    public DeadMessage(Object[] messages) {
        this.relatedMessages = messages;
    }

    public Object[] getMessages() {
        return this.relatedMessages;
    }
}
