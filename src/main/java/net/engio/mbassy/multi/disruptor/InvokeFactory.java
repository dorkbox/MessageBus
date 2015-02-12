package net.engio.mbassy.multi.disruptor;

import com.lmax.disruptor.EventFactory;

/**
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class InvokeFactory implements EventFactory<MessageHolder> {

    public InvokeFactory() {
    }

    @Override
    public MessageHolder newInstance() {
        return new MessageHolder();
    }
}