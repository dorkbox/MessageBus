package dorkbox.util.messagebus.common.simpleq;

public interface HandlerFactory<E> {
    public E newInstance();
}
