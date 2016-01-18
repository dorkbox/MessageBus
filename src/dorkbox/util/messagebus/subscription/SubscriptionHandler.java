package dorkbox.util.messagebus.subscription;

import com.lmax.disruptor.LifecycleAware;
import com.lmax.disruptor.WorkHandler;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public
class SubscriptionHandler implements WorkHandler<SubscriptionHolder>, LifecycleAware {
    private final SubscriptionManager subscriptionManager;

    AtomicBoolean shutdown = new AtomicBoolean(false);

    public
    SubscriptionHandler(final SubscriptionManager subscriptionManager) {
        this.subscriptionManager = subscriptionManager;
    }

    @Override
    public
    void onEvent(final SubscriptionHolder event) throws Exception {
        if (event.doSubscribe) {
            subscriptionManager.subscribe(event.listener);
        }
        else {
            subscriptionManager.unsubscribe(event.listener);
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
