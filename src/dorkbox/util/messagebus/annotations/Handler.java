package dorkbox.util.messagebus.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mark any method of any class(=listener) as a message handler and configure the handler
 * using different properties.
 *
 * @author bennidi
 *         Date: 2/8/12
 * @author dorkbox
 *         Date: 2/2/15
 */
@Retention(value = RetentionPolicy.RUNTIME)
@Inherited
@Target(value = {ElementType.METHOD,ElementType.ANNOTATION_TYPE})
public @interface Handler {

    /**
     * Define whether or not the handler accepts variable arguments it declares in its signature.
     * VarArg "acceptance" means that the handler, handle(String... s), will accept a publication
     * of ("s"), ("s", "s"), or (String[3]{"s", "s", "s"}). By default, handle(String... s) will
     * only handle publications that are exactly an array (String[3]{"s"})
     */
    boolean acceptVarargs() default false;

    /**
     * Define whether or not the handler accepts sub types of the message type it declares in its
     * signature.
     */
    boolean acceptSubtypes() default true;

    /**
     * Enable or disable the handler. Disabled handlers do not receive any messages.
     * This property is useful for quick changes in configuration and necessary to disable
     * handlers that have been declared by a superclass but do not apply to the subclass
     */
    boolean enabled() default true;
}
