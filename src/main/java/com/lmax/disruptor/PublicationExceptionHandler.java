package com.lmax.disruptor;

import com.lmax.disruptor.ExceptionHandler;

import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.error.PublicationError;

public final class PublicationExceptionHandler implements ExceptionHandler {
    private final ErrorHandlingSupport errorHandler;

    public PublicationExceptionHandler(ErrorHandlingSupport errorHandler) {
        this.errorHandler = errorHandler;
    }

    @Override
    public void handleEventException(final Throwable e, final long sequence, final Object event) {
        this.errorHandler.handlePublicationError(new PublicationError()
                            .setMessage("Exception processing: " + sequence + " " + event.getClass() + "(" + event + ")")
                            .setCause(e));
    }

    @Override
    public void handleOnStartException(final Throwable e) {
        this.errorHandler.handlePublicationError(new PublicationError()
                            .setMessage("Error starting the disruptor")
                            .setCause(e));
    }

    @Override
    public void handleOnShutdownException(final Throwable e) {
        this.errorHandler.handlePublicationError(new PublicationError()
                            .setMessage("Error stopping the disruptor")
                            .setCause(e));
    }
}