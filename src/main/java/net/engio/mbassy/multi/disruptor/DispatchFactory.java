package net.engio.mbassy.multi.disruptor;

import com.lmax.disruptor.EventFactory;

/**
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class DispatchFactory implements EventFactory<DispatchHolder> {

    public DispatchFactory() {
    }

    @Override
    public DispatchHolder newInstance() {
        return new DispatchHolder();
    }
}