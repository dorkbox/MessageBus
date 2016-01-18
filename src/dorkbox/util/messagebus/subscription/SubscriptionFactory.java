package dorkbox.util.messagebus.subscription;

import com.lmax.disruptor.EventFactory;

/**
 * @author dorkbox, llc
 *         Date: 1/15/16
 */
public class SubscriptionFactory implements EventFactory<SubscriptionHolder> {

    public
    SubscriptionFactory() {
    }

    @Override
    public
    SubscriptionHolder newInstance() {
        return new SubscriptionHolder();
    }
}
