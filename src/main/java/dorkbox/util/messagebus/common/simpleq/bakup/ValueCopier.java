package dorkbox.util.messagebus.common.simpleq.bakup;

public interface ValueCopier<M> {
    public void copyValues(M source, M dest);
}
