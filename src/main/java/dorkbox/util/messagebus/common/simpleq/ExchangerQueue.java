package dorkbox.util.messagebus.common.simpleq;

import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.lmax.disruptor.MessageHolder;



public class ExchangerQueue {

    private final AtomicInteger availableThreads = new AtomicInteger();

    private final Exchanger<MessageHolder> exchanger = new Exchanger<MessageHolder>();

    ThreadLocal<MessageHolder> holder = new ThreadLocal<MessageHolder>() {
        @Override
        protected MessageHolder initialValue() {
            return new MessageHolder();
        }
    };


    public ExchangerQueue(int numberOfThreads) {
        this.availableThreads.set(numberOfThreads);
    }

    public void transfer(Object message1) throws InterruptedException {
        MessageHolder messageHolder = this.holder.get();
        messageHolder.message1 = message1;
        this.holder.set(this.exchanger.exchange(messageHolder));
    }

    public boolean hasPendingMessages() {
        return false;
    }

    public void tryTransfer(Runnable runnable, long timeout, TimeUnit unit) throws InterruptedException {
        // TODO Auto-generated method stub

    }

    public MessageHolder poll() {
        return null;
    }

    public MessageHolder take(MessageHolder holder) throws InterruptedException {
        return this.exchanger.exchange(holder);
    }
}
