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
package dorkbox.messagebus.error;

import java.util.ArrayDeque;
import java.util.Collection;

@SuppressWarnings("Duplicates")
public final
class ErrorHandler {
    private static final String LINE_SEPARATOR = System.getProperty("line.separator");

    private static final String ERROR_HANDLER_MSG =
                    "INFO: No error handler has been configured to handle exceptions during publication." + LINE_SEPARATOR +
                    "Falling back to console logger." + LINE_SEPARATOR +
                    "Publication error handlers can be added by calling MessageBus.addErrorHandler()" + LINE_SEPARATOR;

    // this handler will receive all errors that occur during message dispatch or message handling
    private final Collection<IPublicationErrorHandler> errorHandlers = new ArrayDeque<IPublicationErrorHandler>();
    private boolean changedDefaults = false;


    public
    ErrorHandler() {
    }

    public synchronized
    void addErrorHandler(IPublicationErrorHandler handler) {
        changedDefaults = true;

        this.errorHandlers.add(handler);
    }

    public synchronized
    void handlePublicationError(PublicationError error) {
        if (!changedDefaults) {
            changedDefaults = true;

            // lazy-set the error handler + default message if none have been set
            if (this.errorHandlers.isEmpty()) {
                this.errorHandlers.add(new IPublicationErrorHandler.ConsoleLogger());
                System.out.println(ERROR_HANDLER_MSG);
            }
        }

        for (IPublicationErrorHandler errorHandler : this.errorHandlers) {
            errorHandler.handleError(error);
        }
    }

    public synchronized
    void handleError(final String error, final Class<?> listenerClass) {
        if (!changedDefaults) {
            changedDefaults = true;

            // lazy-set the error handler + default message if none have been set
            if (this.errorHandlers.isEmpty()) {
                this.errorHandlers.add(new IPublicationErrorHandler.ConsoleLogger());
                System.out.println(ERROR_HANDLER_MSG);
            }
        }

        for (IPublicationErrorHandler errorHandler : this.errorHandlers) {
            errorHandler.handleError(error, listenerClass);
        }
    }
}
