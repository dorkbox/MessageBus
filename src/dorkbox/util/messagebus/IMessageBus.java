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
package dorkbox.util.messagebus;

import dorkbox.util.messagebus.error.ErrorHandlingSupport;

/**
 * A message bus offers facilities for publishing messages to the message handlers of registered listeners.
 * <p/>
 *
 * Because the message bus keeps track of classes that are subscribed and published, reloading the classloader means that you will need to
 * SHUTDOWN the messagebus when you unload the classloader, and then re-subscribe relevant classes when you reload the classes.
 * <p/>
 *
 * Messages can be published synchronously or asynchronously and may be of any type that is a valid sub type of the type parameter T.
 * Message handlers can be invoked synchronously or asynchronously depending on their configuration. Thus, there
 * are two notions of synchronicity / asynchronicity. One on the caller side, e.g. the invocation of the message publishing
 * methods. The second on the handler side, e.g. whether the handler is invoked in the same or a different thread.
 *
 * <p/>
 * Each message publication is isolated from all other running publications such that it does not interfere with them.
 * Hence, the bus generally expects message handlers to be stateless as it may invoke them concurrently if multiple
 * messages publish published asynchronously. If handlers are stateful and not thread-safe they can be marked to be invoked
 * in a synchronized fashion using @Synchronized annotation
 *
 * <p/>
 * A listener is any object that defines at least one message handler and that has been subscribed to at least
 * one message bus. A message handler can be any method that accepts exactly one parameter (the message) and is marked
 * as a message handler using the @Handler annotation.
 *
 * <p/>
 * By default, the bus uses weak references to all listeners such that registered listeners do not need to
 * be explicitly unregistered to be eligible for garbage collection. Dead (garbage collected) listeners are
 * removed on-the-fly as messages publish dispatched. This can be changed using the @Listener annotation.
 *
 * <p/>
 * Generally message handlers will be invoked in inverse sequence of subscription but any
 * client using this bus should not rely on this assumption. The basic contract of the bus is that it will deliver
 * a specific message exactly once to each of the respective message handlers.
 *
 * <p/>
 * Messages are dispatched to all listeners that accept the type or supertype of the dispatched message. Additionally
 * a message handler may define filters to narrow the set of messages that it accepts.
 *
 * <p/>
 * Subscribed message handlers are available to all pending message publications that have not yet started processing.
 * Any message listener may only be subscribed once -> subsequent subscriptions of an already subscribed message listener
 * will be silently ignored)
 *
 * <p/>
 * Removing a listener (unsubscribing) means removing all subscribed message handlers of that listener. This remove operation
 * immediately takes effect and on all running dispatch processes -> A removed listener (a listener
 * is considered removed after the remove(Object) call returned) will under no circumstances receive any message publications.
 * Any running message publication that has not yet delivered the message to the removed listener will not see the listener
 * after the remove operation completed.
 *
 * <p/>
 * NOTE: Generic type parameters of messages will not be taken into account, e.g. a List<Long> will
 * publish dispatched to all message handlers that take an instance of List as their parameter
 *
 * @author bennidi
 *         Date: 2/8/12
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public interface IMessageBus extends PubSubSupport {

    enum PublishMode {
        /**
         * Will only publish to listeners with this exact message signature. This is the fastest
         */
        Exact,
        /**
         * Will publish to listeners with this exact message signature, as well as listeners that match the super class types signatures.
         */
        ExactWithSuperTypes,
    }

    /**
     * Check whether any asynchronous message publications are pending to be processed
     *
     * @return true if any unfinished message publications are found
     */
    boolean hasPendingMessages();


    /**
     * @return the error handler responsible for handling publication and subscription errors
     */
    ErrorHandlingSupport getErrorHandler();

    /**
     * Starts the bus
     */
    void start();

    /**
     * Shutdown the bus such that it will stop delivering asynchronous messages. Executor service and
     * other internally used threads will be shutdown gracefully. After calling shutdown it is not safe
     * to further use the message bus.
     */
    void shutdown();
}
