package dorkbox.util.messagebus.error;

import java.util.ArrayDeque;
import java.util.Collection;

/**
 *
 */
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
    void start() {
        synchronized (this.errorHandlers) {
            if (this.errorHandlers.isEmpty()) {
                this.errorHandlers.add(new IPublicationErrorHandler.ConsoleLogger());
                System.out.println(ERROR_HANDLER_MSG);
            }
        }
    }
}
