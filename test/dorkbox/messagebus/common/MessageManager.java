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
package dorkbox.messagebus.common;

import dorkbox.messagebus.messages.IMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User: benjamin
 * Date: 6/26/13
 */
public class MessageManager {
    private static final Logger LOG =
            LoggerFactory.getLogger(MessageManager.class);

    private static final Object mapObject = new Object();
    private ConcurrentHashMap<MessageContext, Object> messages = new ConcurrentHashMap<MessageContext, Object>();


    public <T extends IMessage> T create(Class<T> messageType, int expectedCount, Class ...listeners){
        T message;
        try {
            message = messageType.newInstance();
            register(message, expectedCount, listeners);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return message;
    }

    public <T extends IMessage> T create(Class<T> messageType, int expectedCount, Collection<Class> listeners){
        T message;
        try {
            message = messageType.newInstance();
            register(message, expectedCount, listeners);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return message;
    }

    public <T extends IMessage> void register(T message, int expectedCount, Class ...listeners){
        try {
            this.messages.put(new MessageContext(expectedCount, message, listeners), mapObject);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public <T extends IMessage> void register(T message, int expectedCount, Collection<Class> listeners){
        try {
            this.messages.put(new MessageContext(expectedCount, message, listeners), mapObject);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void waitForMessages(int timeoutInMs){
        long start = System.currentTimeMillis();
        while(System.currentTimeMillis() - start < timeoutInMs && this.messages.size() > 0){
            // check each created message once
            for(MessageContext messageCtx : this.messages.keySet()){
                boolean handledCompletely = true;
                for(Class listener : messageCtx.getListeners()){
                    handledCompletely &= messageCtx.getMessage().getTimesHandled(listener) == messageCtx.getExpectedCount();
                }
                // remove the ones that were handled as expected
                if(handledCompletely){
                    logSuccess(messageCtx);
                    this.messages.remove(messageCtx);
                }

            }
            pause(100);
        }
        if(this.messages.size() > 0){
            logFailingMessages(this.messages);
            throw new RuntimeException("Message were not fully processed in given time");
        }


    }

    private void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

   private void logSuccess(MessageContext mCtx){
       LOG.info("Message " + mCtx.getMessage() + " was successfully handled " + mCtx.getExpectedCount() + " times by " + mCtx.printListeners());
   }



    private void logFailingMessages(ConcurrentHashMap<MessageContext, Object> failing){
        StringBuilder errorMessage = new StringBuilder();
        errorMessage.append("Failing messages:\n");
        for(MessageContext failingMessage : failing.keySet()) {
            errorMessage.append(failingMessage);
        }
        LOG.info(errorMessage.toString());
    }

    private class MessageContext{

        private long expectedCount;
        private IMessage message;
        private Class[] listeners;

        private MessageContext(long expectedCount, IMessage message, Class[] listeners) {
            this.expectedCount = expectedCount;
            this.message = message;
            this.listeners = listeners;
        }

        private MessageContext(long expectedCount, IMessage message, Collection<Class> listeners) {
            this.expectedCount = expectedCount;
            this.message = message;
            this.listeners = listeners.toArray(new Class[]{});
        }

        private long getExpectedCount() {
            return this.expectedCount;
        }

        private IMessage getMessage() {
            return this.message;
        }

        private Class[] getListeners() {
            return this.listeners;
        }

        private String printListeners(){
            StringBuilder listenersAsString = new StringBuilder();
            for(Class listener : this.listeners){
                listenersAsString.append(listener.getName());
                listenersAsString.append(",");
            }
            return listenersAsString.toString();
        }

        @Override
        public String toString() {
            // TODO: actual count of listeners
            return this.message.getClass().getSimpleName() + "{" +
                    "expectedCount=" + this.expectedCount +
                    ", listeners=" + printListeners() +
                    '}';
        }
    }


}
