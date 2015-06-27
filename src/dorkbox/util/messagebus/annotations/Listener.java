package dorkbox.util.messagebus.annotations;

import java.lang.annotation.*;

/**
 * This annotation is meant to carry configuration that is shared among all instances of the annotated
 * listener. Supported configurations are:
 * <p/>
 * Reference type: The bus will use either strong or weak references to its registered listeners,
 * depending on which reference type (@see References) is set
 *
 * @author bennidi
 */
@Retention(value = RetentionPolicy.RUNTIME)
@Target(value = {ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Inherited
public
@interface Listener {}
