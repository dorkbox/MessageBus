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
 *
 *
 * Copyright 2015 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.messagebus;


/**
 * This interface defines the very basic message publication semantics according to the publish subscribe pattern.
 * Listeners can be subscribed and unsubscribed using the corresponding methods. When a listener is subscribed its
 * handlers will be registered and start to receive matching message publications.
 *
 * @author bennidi
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public interface PubSubSupport {

    /**
     * Subscribe all handlers of the given listener. Any listener is only subscribed once
     * subsequent subscriptions of an already subscribed listener will be silently ignored
     */
    void subscribe(Object listener);

    /**
     * Immediately remove all registered message handlers (if any) of the given listener.
     * When this call returns all handlers have effectively been removed and will not
     * receive any messages (provided that running publications (iterators) in other threads
     * have not yet obtained a reference to the listener)
     * <p>
     * A call to this method passing any object that is not subscribed will not have any effect and is silently ignored.
     */
    void unsubscribe(Object listener);


    /**
     * Synchronously publish a message to all registered listeners. This includes listeners
     * defined for super types of the given message type, provided they are not configured
     * to reject valid subtypes. The call returns when all matching handlers of all registered
     * listeners have been notified (invoked) of the message.
     */
    void publish(Object message);

    /**
     * Synchronously publish <b>TWO</b> messages to all registered listeners (that match the signature). This
     * includes listeners defined for super types of the given message type, provided they are not configured
     * to reject valid subtypes. The call returns when all matching handlers of all registered listeners have
     * been notified (invoked) of the message.
     */
    void publish(Object message1, Object message2);

    /**
     * Synchronously publish <b>THREE</b> messages to all registered listeners (that match the signature). This
     * includes listeners defined for super types of the given message type, provided they are not configured
     * to reject valid subtypes. The call returns when all matching handlers of all registered listeners have
     * been notified (invoked) of the message.
     */
    void publish(Object message1, Object message2, Object message3);

    /**
     * Publish the message asynchronously to all registered listeners (that match the signature). This includes
     * listeners defined for super types of the given message type, provided they are not configured to reject
     * valid subtypes. The call returns when all matching handlers of all registered listeners have been notified
     * (invoked) of the message.
     * <p>
     * <p>
     * The behavior of this method depends on availability of workers. If all workers are busy, then this method
     * will block until there is an available worker. If workers are available, then this method will immediately
     * return.
     */
    void publishAsync(Object message);

    /**
     * Publish <b>TWO</b> messages asynchronously to all registered listeners (that match the signature). This
     * includes listeners defined for super types of the given message type, provided they are not configured
     * to reject valid subtypes. The call returns when all matching handlers of all registered listeners have
     * been notified (invoked) of the message.
     * <p>
     * <p>
     * The behavior of this method depends on availability of workers. If all workers are busy, then this method
     * will block until there is an available worker. If workers are available, then this method will immediately
     * return.
     */
    void publishAsync(Object message1, Object message2);

    /**
     * Publish <b>THREE</b> messages asynchronously to all registered listeners (that match the signature). This
     * includes listeners defined for super types of the given message type, provided they are not configured to
     * reject valid subtypes. The call returns when all matching handlers of all registered listeners have been
     * notified (invoked) of the message.
     * <p>
     * <p>
     * The behavior of this method depends on availability of workers. If all workers are busy, then this method
     * will block until there is an available worker. If workers are available, then this method will immediately
     * return.
     */
    void publishAsync(Object message1, Object message2, Object message3);
}
