package dorkbox.util.messagebus.listener;

import java.lang.reflect.Method;
import java.util.ArrayList;

import dorkbox.util.messagebus.annotations.Handler;
import dorkbox.util.messagebus.common.ReflectionUtils;

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
    public MessageHandler[] getMessageHandlers(final Class<?> target) {

        // get all handlers (this will include all (inherited) methods directly annotated using @Handler)
        final Method[] allMethods = ReflectionUtils.getMethods(target);
        final int length = allMethods.length;

        final ArrayList<MessageHandler> finalMethods = new ArrayList<MessageHandler>(length);
        Method method;

        for (int i=0;i<length;i++) {
            method = allMethods[i];

            // retain only those that are at the bottom of their respective class hierarchy (deepest overriding method)
            if (!ReflectionUtils.containsOverridingMethod(allMethods, method)) {

                // for each handler there will be no overriding method that specifies @Handler annotation
                // but an overriding method does inherit the listener configuration of the overwritten method
                final Handler handler = ReflectionUtils.getAnnotation(method, Handler.class);
                if (handler == null || !handler.enabled()) {
                    // disabled or invalid listeners are ignored
                    continue;
                }

                Method overriddenHandler = ReflectionUtils.getOverridingMethod(method, target);
                if (overriddenHandler == null) {
                    overriddenHandler = method;
                }

                // if a handler is overwritten it inherits the configuration of its parent method
                finalMethods.add(new MessageHandler(overriddenHandler, handler));
            }
        }

        MessageHandler[] array = finalMethods.toArray(new MessageHandler[finalMethods.size()]);
        return array;
    }
}
