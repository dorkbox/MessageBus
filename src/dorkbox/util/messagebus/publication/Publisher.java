package dorkbox.util.messagebus.publication;

public interface Publisher {
    void publish(Object message1);

    void publish(Object message1, Object message2);

    void publish(Object message1, Object message2, Object message3);

    void publish(Object[] messages);
}
