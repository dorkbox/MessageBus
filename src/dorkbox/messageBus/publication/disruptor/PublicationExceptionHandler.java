/*
 * Copyright 2016 dorkbox, llc
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
package dorkbox.messageBus.publication.disruptor;

import com.lmax.disruptor.ExceptionHandler;

import dorkbox.messageBus.error.ErrorHandler;
import dorkbox.messageBus.error.PublicationError;

/**
 * @author dorkbox, llc Date: 2/3/16
 */
public final class PublicationExceptionHandler<T> implements ExceptionHandler<T> {
    private final ErrorHandler errorHandler;

    public PublicationExceptionHandler(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }

    @Override
    public void handleEventException(final Throwable e, final long sequence, final T event) {
        this.errorHandler.handlePublicationError(new PublicationError()
                            .setMessage("Exception on lmax disruptor processing: " + sequence + " " + event.getClass() + "(" + event + ")")
                            .setCause(e));
    }

    @Override
    public void handleOnStartException(final Throwable e) {
        this.errorHandler.handlePublicationError(new PublicationError()
                            .setMessage("Error starting the lmax disruptor")
                            .setCause(e));
    }

    @Override
    public void handleOnShutdownException(final Throwable e) {
        this.errorHandler.handlePublicationError(new PublicationError()
                            .setMessage("Error stopping the lmax disruptor")
                            .setCause(e));
    }
}
