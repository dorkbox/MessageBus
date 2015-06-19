package dorkbox.util.messagebus.subscription;

import dorkbox.util.messagebus.utils.VarArgUtils;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public interface Subscriber {
    float LOAD_FACTOR = 0.8F;

    AtomicBoolean getVarArgPossibility();

    VarArgUtils getVarArgUtils();

    void register(Class<?> listenerClass, int handlersSize, Subscription[] subsPerListener);

    void shutdown();

    void clear();

    ArrayList<Subscription> getExactAsArray(Class<?> superClass);

    ArrayList<Subscription> getExactAsArray(Class<?> superClass1, Class<?> superClass2);

    ArrayList<Subscription> getExactAsArray(Class<?> superClass1, Class<?> superClass2, Class<?> superClass3);


    Subscription[] getExact(Class<?> deadMessageClass);

    Subscription[] getExact(Class<?> messageClass1, Class<?> messageClass2);

    Subscription[] getExact(Class<?> messageClass1, Class<?> messageClass2, Class<?> messageClass3);


    Subscription[] getExactAndSuper(Class<?> messageClass);

    Subscription[] getExactAndSuper(Class<?> messageClass1, Class<?> messageClass2);

    Subscription[] getExactAndSuper(Class<?> messageClass1, Class<?> messageClass2, Class<?> messageClass3);
}
