package dorkbox.util.messagebus.common.simpleq.jctools;

abstract class MpmcArrayQueueL2Pad<E> extends MpmcArrayQueueProducerField<E> {
    long p20, p21, p22, p23, p24, p25, p26;
    long p30, p31, p32, p33, p34, p35, p36, p37;

    public MpmcArrayQueueL2Pad(int capacity) {
        super(capacity);
    }
}
