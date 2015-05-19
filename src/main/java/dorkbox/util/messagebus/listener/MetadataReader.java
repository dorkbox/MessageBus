package dorkbox.util.messagebus.listener;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Iterator;

import dorkbox.util.messagebus.annotations.Handler;
import dorkbox.util.messagebus.common.ReflectionUtils;
import dorkbox.util.messagebus.common.thread.ConcurrentSet;

/**
 * The meta data reader is responsible for parsing and validating message handler configurations.
 *
 * @author bennidi
 *         Date: 11/16/12
 * @author dorkbox
 *         Date: 2/2/15
 */
public class MetadataReader {

    // get all listeners defined by the given class (includes
    // listeners defined in super classes)
    public MessageListener getMessageListener(Class<?> target, float loadFactor, int stripeSize) {

        // get all handlers (this will include all (inherited) methods directly annotated using @Handler)
        Collection<Method> allHandlers = ReflectionUtils.getMethods(target);

        // retain only those that are at the bottom of their respective class hierarchy (deepest overriding method)
        Collection<Method> bottomMostHandlers = new ConcurrentSet<Method>(allHandlers.size(), loadFactor, stripeSize);
        Iterator<Method> iterator;
        Method handler;

        for (iterator = allHandlers.iterator(); iterator.hasNext();) {
            handler  = iterator.next();

            if (!ReflectionUtils.containsOverridingMethod(allHandlers, handler)) {
                bottomMostHandlers.add(handler);
            }
        }

        MessageListener listenerMetadata = new MessageListener(target, bottomMostHandlers.size(), loadFactor, stripeSize);

        // for each handler there will be no overriding method that specifies @Handler annotation
        // but an overriding method does inherit the listener configuration of the overwritten method

        for (iterator = bottomMostHandlers.iterator(); iterator.hasNext();) {
            handler = iterator.next();

            Handler handlerConfig = ReflectionUtils.getAnnotation(handler, Handler.class);
            if (handlerConfig == null || !handlerConfig.enabled()) {
                continue; // disabled or invalid listeners are ignored
            }

            Method overriddenHandler = ReflectionUtils.getOverridingMethod(handler, target);
            if (overriddenHandler == null) {
                overriddenHandler = handler;
            }

            // if a handler is overwritten it inherits the configuration of its parent method
            MessageHandler handlerMetadata = new MessageHandler(overriddenHandler, handlerConfig, listenerMetadata);
            listenerMetadata.addHandler(handlerMetadata);
        }
        return listenerMetadata;
    }
}
