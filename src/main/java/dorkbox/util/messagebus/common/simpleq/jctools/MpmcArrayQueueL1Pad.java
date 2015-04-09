package dorkbox.util.messagebus.common.simpleq.jctools;

abstract class MpmcArrayQueueL1Pad<E> extends ConcurrentSequencedCircularArrayQueue<E> {
    long p10, p11, p12, p13, p14, p15, p16;
    long p30, p31, p32, p33, p34, p35, p36, p37;

    public MpmcArrayQueueL1Pad(int capacity) {
        super(capacity);
    }
}
