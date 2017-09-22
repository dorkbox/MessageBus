/*
 * Copyright 2012 Benjamin Diedrichsen
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

import java.io.BufferedReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import dorkbox.messageBus.annotations.Handler;
import dorkbox.messageBus.common.MessageHandler;
import dorkbox.util.messagebus.common.AssertSupport;

/**
 *
 * @author bennidi
 *         Date: 12/16/12
 */
public class MetadataReaderTest extends AssertSupport {

    @Test
    public void testListenerWithoutInheritance() {
        MessageHandler[] allHandlers = MessageHandler.get(MessageListener1.class);
        ListenerValidator validator = new ListenerValidator()
                .expectHandlers(2, String.class)
                .expectHandlers(2, Object.class)
                .expectHandlers(1, BufferedReader.class);
        validator.check(allHandlers);
    }

    /*
    public void testInterfaced() {
        MessageListener listener = reader.getMessageListener(InterfacedListener.class);
        ListenerValidator validator = new ListenerValidator()
                .expectHandlers(1, Object.class);
        validator.check(listener);
    }  WIP */


    @Test
    public void testListenerWithInheritance() {
        MessageHandler[] allHandlers = MessageHandler.get(MessageListener2.class);
        ListenerValidator validator = new ListenerValidator()
                .expectHandlers(2, String.class)
                .expectHandlers(2, Object.class)
                .expectHandlers(1, BufferedReader.class);
        validator.check(allHandlers);
    }

    @Test
    public void testListenerWithInheritanceOverriding() {
        MessageHandler[] allHandlers = MessageHandler.get(MessageListener3.class);
        ListenerValidator validator = new ListenerValidator()
                .expectHandlers(0, String.class)
                .expectHandlers(2, Object.class)
                .expectHandlers(0, BufferedReader.class);
        validator.check(allHandlers);
    }

    public static class NClasses {
        final Class<?>[] messageTypes;

        public NClasses(Class<?> nClass) {
            this.messageTypes = new Class<?>[] {nClass};
        }

        public NClasses(Class<?>... messageTypes) {
            this.messageTypes = messageTypes;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + Arrays.hashCode(this.messageTypes);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            NClasses other = (NClasses) obj;
            if (!Arrays.equals(this.messageTypes, other.messageTypes)) {
                return false;
            }
            return true;
        }
    }

    private class ListenerValidator {
        private Map<NClasses, Integer> handlers = new HashMap<NClasses, Integer>();

        public ListenerValidator expectHandlers(Integer count, Class<?> requiredMessageType) {
            NClasses nClasses = new NClasses(requiredMessageType);

            this.handlers.put(nClasses, count);
            return this;
        }

        public ListenerValidator expectHandlers(Integer count, Class<?>... messageTypes) {
            NClasses nClasses = new NClasses(messageTypes);

            this.handlers.put(nClasses, count);
            return this;
        }

        public void check(MessageHandler[] allHandlers){
            for (Map.Entry<NClasses, Integer> expectedHandler: this.handlers.entrySet()) {
                NClasses key = expectedHandler.getKey();
                List<MessageHandler> handlers2 = pruneHandlers(allHandlers, key.messageTypes);

                if (expectedHandler.getValue() > 0){
                    assertTrue(!handlers2.isEmpty());
                }
                else{
                    assertFalse(!handlers2.isEmpty());
                }
                assertEquals(expectedHandler.getValue(), handlers2.size());
            }
        }

        // for testing
        public List<MessageHandler> pruneHandlers(MessageHandler[] allHandlers, Class<?>... messageTypes) {
            List<MessageHandler> matching = new LinkedList<MessageHandler>();

            for (MessageHandler handler : allHandlers) {
                if (handlesMessage(handler, messageTypes)) {
                    matching.add(handler);
                }
            }
            return matching;
        }
    }

    // a simple event listener
    public class MessageListener1 {

        @Handler(acceptSubtypes = false)
        public void handleObject(Object o) {

        }

        @Handler
        public void handleAny(Object o) {

        }


        @Handler
        public void handleString(String s) {

        }
    }

    // the same handlers as its super class
    public class MessageListener2 extends MessageListener1 {

        // redefine handler implementation (not configuration)
        @Override
        public void handleString(String s) {

        }
    }

    public class MessageListener3 extends MessageListener2 {

        // narrow the handler
        @Override
        @Handler(acceptSubtypes = false)
        public void handleAny(Object o) {

        }

        @Override
        @Handler(enabled = false)
        public void handleString(String s) {

        }
    }




    @Test
    public void testMultipleSignatureListenerWithoutInheritance() {
        MessageHandler[] allHandlers = MessageHandler.get(MultiMessageListener1.class);
        ListenerValidator validator = new ListenerValidator()
                .expectHandlers(7, String.class)
                .expectHandlers(9, String.class, String.class)
                .expectHandlers(9, String.class, String.class, String.class)
                .expectHandlers(3, String.class, String[].class)
                .expectHandlers(1, String.class, String[].class, String[].class)
                .expectHandlers(6, String[].class)
                .expectHandlers(3, String[].class, String[].class)
                .expectHandlers(2, Object.class)
                .expectHandlers(2, String.class, Object.class)
                .expectHandlers(2, String.class, Object[].class)
                ;
        validator.check(allHandlers);
    }

    public class MultiMessageListener1 {

        @Handler public void handleString1(String s) {}
        @Handler public void handleString2(String s, String s1) {}
        @Handler public void handleString3(String s, String s1, String s2) {}

        @Handler public void handleStringN(String... s1) {}
        @Handler public void handleStringArray(String[] s1) {}

        @Handler public void handleStringN(Object... s1) {}
        @Handler public void handleStringArray(Object[] s1) {}

        @Handler public void handleString1plusN(String s, String... s1) {}
        @Handler public void handleString1plusN(String s, Object... s1) {}

        @Handler public void handleString2plusN(String s, String s1, String... s2) {}
        @Handler public void handleString2plusN(String s, Object s1, String... s2) {}

        @Handler public void handleStringXplus1(String[] s, String s1) {}

        @Handler public void handleStringXplusN(String[] s, String... s1) {}
        @Handler public void handleStringXplusN(String[] s, Object... s1) {}

        @Handler public void handleStringXplus1plusN(String[] s, String s1, String... s2) {}
        @Handler public void handleStringXplus1plusN(String[] s, String s1, Object... o) {}

        @Handler public void handleStringXplus1plusN(String[] s, Object o, Object... o1) {}

    }

    /**
     * @return true if the message types are handled
     */
    private static boolean handlesMessage(MessageHandler handler, Class<?> messageType) {
        Class<?>[] handledMessages = handler.getHandledMessages();
        int handledLength = handledMessages.length;

        if (handledLength != 1) {
            return false;
        }

        if (handler.acceptsSubtypes()) {
            if (!handledMessages[0].isAssignableFrom(messageType)) {
                return false;
            }
        } else {
            if (handledMessages[0] != messageType) {
                return false;
            }
        }

        return true;
    }

    /**
     * @return true if the message types are handled
     */
    private static boolean handlesMessage(MessageHandler handler, Class<?> messageType1, Class<?> messageType2) {
        Class<?>[] handledMessages = handler.getHandledMessages();
        int handledLength = handledMessages.length;

        if (handledLength != 2) {
            return false;
        }

        if (handler.acceptsSubtypes()) {
            if (!handledMessages[0].isAssignableFrom(messageType1)) {
                return false;
            }
            if (!handledMessages[1].isAssignableFrom(messageType2)) {
                return false;
            }
        } else {
            if (handledMessages[0] != messageType1) {
                return false;
            }
            if (handledMessages[1] != messageType2) {
                return false;
            }
        }

        return true;
    }

    /**
     * @return true if the message types are handled
     */
    private static boolean handlesMessage(MessageHandler handler, Class<?> messageType1, Class<?> messageType2, Class<?> messageType3) {
        Class<?>[] handledMessages = handler.getHandledMessages();
        int handledLength = handledMessages.length;

        if (handledLength != 3) {
            return false;
        }

        if (handler.acceptsSubtypes()) {
            if (!handledMessages[0].isAssignableFrom(messageType1)) {
                return false;
            }
            if (!handledMessages[1].isAssignableFrom(messageType2)) {
                return false;
            }
            if (!handledMessages[2].isAssignableFrom(messageType3)) {
                return false;
            }
        } else {
            if (handledMessages[0] != messageType1) {
                return false;
            }
            if (handledMessages[1] != messageType2) {
                return false;
            }
            if (handledMessages[2] != messageType3) {
                return false;
            }
        }

        return true;
    }

    /**
     * @return true if the message types are handled
     */
    private static boolean handlesMessage(MessageHandler handler, Class<?>... messageTypes) {
        Class<?>[] handledMessages = handler.getHandledMessages();

        int handledLength = handledMessages.length;
        int handledLengthMinusVarArg = handledLength-1;

        int messagesLength = messageTypes.length;

        // do we even have enough to even CHECK the var-arg?
        if (messagesLength < handledLengthMinusVarArg) {
            // totally wrong number of args
            return false;
        }

        // check BEFORE var-arg in handler (var-arg can ONLY be last element in array)
        if (handledLengthMinusVarArg <= messagesLength) {
            if (handler.acceptsSubtypes()) {
                for (int i = 0; i < handledLengthMinusVarArg; i++) {
                    Class<?> handledMessage = handledMessages[i];
                    Class<?> messageType = messageTypes[i];

                    if (!handledMessage.isAssignableFrom(messageType)) {
                        return false;
                    }
                }
            } else {
                for (int i = 0; i < handledLengthMinusVarArg; i++) {
                    Class<?> handledMessage = handledMessages[i];
                    Class<?> messageType = messageTypes[i];

                    if (handledMessage != messageType) {
                        return false;
                    }
                }
            }
        }

        // do we even HAVE var-arg?
        if (!handledMessages[handledLengthMinusVarArg].isArray()) {
            // DO NOT HAVE VAR_ARG PRESENT IN HANDLERS

            // fast exit
            if (handledLength != messagesLength) {
                return false;
            }

            // compare remaining arg
            Class<?> handledMessage = handledMessages[handledLengthMinusVarArg];
            Class<?> messageType = messageTypes[handledLengthMinusVarArg];

            if (handler.acceptsSubtypes()) {
                if (!handledMessage.isAssignableFrom(messageType)) {
                    return false;
                }
            } else {
                if (handledMessage != messageType) {
                    return false;
                }
            }
            // all args are dandy
            return true;
        }

        // WE HAVE VAR_ARG PRESENT IN HANDLER

        // do we have enough args to NEED to check the var-arg?
        if (handledLengthMinusVarArg == messagesLength) {
            // var-arg doesn't need checking
            return true;
        }

        // then check var-arg in handler

        // all the args to check for the var-arg MUST be the same! (ONLY ONE ARRAY THOUGH CAN BE PRESENT)
        int messagesLengthMinusVarArg = messagesLength-1;

        Class<?> typeCheck = messageTypes[handledLengthMinusVarArg];
        for (int i = handledLengthMinusVarArg; i < messagesLength; i++) {
            Class<?> t1 = messageTypes[i];
            if (t1 != typeCheck) {
                return false;
            }
        }

        // if we got this far, then the args are the same type. IF we have more than one, AND they are arrays, NOPE!
        if (messagesLength - handledLengthMinusVarArg > 1 && messageTypes[messagesLengthMinusVarArg].isArray()) {
            return false;
        }

        // are we comparing array -> array or string -> array
        Class<?> componentType;
        if (messageTypes[messagesLengthMinusVarArg].isArray()) {
            componentType = handledMessages[handledLengthMinusVarArg];
        } else {
            componentType = handledMessages[handledLengthMinusVarArg].getComponentType();
        }

        if (handler.acceptsSubtypes()) {
            return componentType.isAssignableFrom(typeCheck);
        } else {
            return typeCheck == componentType;
        }
    }
}
