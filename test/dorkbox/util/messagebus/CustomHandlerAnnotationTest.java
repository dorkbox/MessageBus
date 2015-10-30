/*
 * Copyright 2013 Benjamin Diedrichsen
 *
 * Permission is hereby granted, free  of charge, to any person obtaining
 * a  copy  of this  software  and  associated  documentation files  (the
 * "Software"), to  deal in  the Software without  restriction, including
 * without limitation  the rights to  use, copy, modify,  merge, publish,
 * distribute,  sublicense, and/or sell  copies of  the Software,  and to
 * permit persons to whom the Software  is furnished to do so, subject to
 * the following conditions:
 *
 * The  above  copyright  notice  and  this permission  notice  shall  be
 * included in all copies or substantial portions of the Software.
 *
 * THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
 * EXPRESS OR  IMPLIED, INCLUDING  BUT NOT LIMITED  TO THE  WARRANTIES OF
 * MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE,  ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package dorkbox.util.messagebus;

import dorkbox.util.messagebus.annotations.Handler;
import dorkbox.util.messagebus.annotations.Synchronized;
import dorkbox.util.messagebus.common.MessageBusTest;
import org.junit.Test;

import java.lang.annotation.*;
import java.util.HashSet;
import java.util.Set;

/**
 * Tests a custom handler annotation with a @Handler meta annotation and a default filter.
 */
public class CustomHandlerAnnotationTest extends MessageBusTest
{
    /**
    * Handler annotation that adds a default filter on the NamedMessage.
    * Enveloped is in no way required, but simply added to test a meta enveloped annotation.
    */
    @Retention(value = RetentionPolicy.RUNTIME)
    @Inherited
    @Handler()
    @Synchronized
    @Target(value = { ElementType.METHOD, ElementType.ANNOTATION_TYPE })
    static @interface NamedMessageHandler
    {
        /**
        * @return The message names supported.
        */
        String[] value();
    }

    /**
     * Handler annotation that adds a default filter on the NamedMessage.
     * Enveloped is in no way required, but simply added to test a meta enveloped annotation.
     */
    @Retention(value = RetentionPolicy.RUNTIME)
    @Inherited
    @NamedMessageHandler("messageThree")
    static @interface MessageThree {}



    /**
    * Test enveloped meta annotation.
    */
    @Retention(value = RetentionPolicy.RUNTIME)
    @Target(value = { ElementType.METHOD, ElementType.ANNOTATION_TYPE })
    @Inherited
    @Handler()
    static @interface EnvelopedNamedMessageHandler
    {
        /**
        * @return The message names supported.
        */
        String[] value();
    }

    static class NamedMessage
    {
        private String name;

        NamedMessage( String name ) {
            this.name = name;
        }

        public String getName() {
            return this.name;
        }
    }

    static class NamedMessageListener
    {
        final Set<NamedMessage> handledByOne = new HashSet<NamedMessage>();
        final Set<NamedMessage> handledByTwo = new HashSet<NamedMessage>();
        final Set<NamedMessage> handledByThree = new HashSet<NamedMessage>();

        @NamedMessageHandler({ "messageOne", "messageTwo" })
        void handlerOne( NamedMessage message ) {
            this.handledByOne.add( message );
        }

        @MessageThree
        void handlerThree( NamedMessage message ) {
            this.handledByThree.add( message );
        }
    }

    @Test
    public void testMetaHandlerFiltering() {
        MessageBus bus = createBus();

        NamedMessageListener listener = new NamedMessageListener();
        bus.subscribe( listener );

        NamedMessage messageOne = new NamedMessage( "messageOne" );
        NamedMessage messageTwo = new NamedMessage( "messageTwo" );
        NamedMessage messageThree = new NamedMessage( "messageThree" );

        bus.publish( messageOne );
        bus.publish( messageTwo );
        bus.publish( messageThree );

        assertEquals(2, listener.handledByOne.size());
        assertTrue( listener.handledByOne.contains( messageOne ) );
        assertTrue(listener.handledByOne.contains(messageTwo));

        assertEquals(2, listener.handledByTwo.size());
        assertTrue( listener.handledByTwo.contains( messageTwo ) );
        assertTrue( listener.handledByTwo.contains( messageThree ) );

        assertEquals(1, listener.handledByThree.size());
        assertTrue( listener.handledByThree.contains( messageThree ) );
    }
}
