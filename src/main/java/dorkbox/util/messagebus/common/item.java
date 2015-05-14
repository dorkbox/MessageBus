package dorkbox.util.messagebus.common;

public abstract class item<T> {
    public volatile Entry<T> head; // reference to the first element
}
