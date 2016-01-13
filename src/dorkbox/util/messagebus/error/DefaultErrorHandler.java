/*
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
package dorkbox.util.messagebus.error;

import java.util.ArrayDeque;
import java.util.Collection;

public
class DefaultErrorHandler implements ErrorHandlingSupport {

    private static final String ERROR_HANDLER_MSG =
                    "INFO: No error handler has been configured to handle exceptions during publication.\n" +
                    "Publication error handlers can be added by bus.getErrorHandler().addErrorHandler()\n" +
                    "Falling back to console logger.";

    // this handler will receive all errors that occur during message dispatch or message handling
    private final Collection<IPublicationErrorHandler> errorHandlers = new ArrayDeque<IPublicationErrorHandler>();


    public
    DefaultErrorHandler() {
    }

    @Override
    public final
    void addErrorHandler(IPublicationErrorHandler handler) {
        synchronized (this.errorHandlers) {
            this.errorHandlers.add(handler);
        }
    }

    @Override
    public final
    void handlePublicationError(PublicationError error) {
        synchronized (this.errorHandlers) {
            for (IPublicationErrorHandler errorHandler : this.errorHandlers) {
                errorHandler.handleError(error);
            }
        }
    }

    @Override
    public
    void handleError(final String error, final Class<?> listenerClass) {
        synchronized (this.errorHandlers) {
            for (IPublicationErrorHandler errorHandler : this.errorHandlers) {
                errorHandler.handleError(error, listenerClass);
            }
        }
    }

    @Override
    public
    void init() {
        synchronized (this.errorHandlers) {
            if (this.errorHandlers.isEmpty()) {
                this.errorHandlers.add(new IPublicationErrorHandler.ConsoleLogger());
                System.out.println(ERROR_HANDLER_MSG);
            }
        }
    }
}
