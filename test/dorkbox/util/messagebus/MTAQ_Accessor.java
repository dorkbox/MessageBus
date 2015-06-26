package dorkbox.util.messagebus;

/**
 *
 */
public
class MTAQ_Accessor {

    MpmcMultiTransferArrayQueue mpmcMultiTransferArrayQueue;

    public
    MTAQ_Accessor(final int consumerCount) {
        mpmcMultiTransferArrayQueue = new MpmcMultiTransferArrayQueue(consumerCount);
    }

    public
    Object poll() {
        return mpmcMultiTransferArrayQueue.poll();
    }

    public
    boolean offer(final Object item) {
        return mpmcMultiTransferArrayQueue.offer(item);
    }

    public
    void take(final MultiNode node) throws InterruptedException {
        mpmcMultiTransferArrayQueue.take(node);
    }

    public
    void transfer(final Object item, final int type) throws InterruptedException {
        mpmcMultiTransferArrayQueue.transfer(item, type);
    }
}
