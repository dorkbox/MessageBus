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
package dorkbox.util.messagebus.error;

import java.util.Arrays;

/**
 * Publication errors are created when object publication fails
 * for some reason and contain details as to the cause and location
 * where they occurred.
 * <p/>
 *
 * @author bennidi
 *         Date: 2/22/12
 *         Time: 4:59 PM
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public
class PublicationError {

    // Internal state
    private Throwable cause;
    private String message;
    private Object[] publishedObjects;


    /**
     * Default constructor.
     */
    public
    PublicationError() {
        super();
    }

    /**
     * @return The Throwable giving rise to this PublicationError.
     */
    public
    Throwable getCause() {
        return this.cause;
    }

    /**
     * Assigns the cause of this PublicationError.
     *
     * @param cause A Throwable which gave rise to this PublicationError.
     * @return This PublicationError.
     */
    public
    PublicationError setCause(Throwable cause) {
        this.cause = cause;
        return this;
    }

    public
    String getMessage() {
        return this.message;
    }

    public
    PublicationError setMessage(String message) {
        this.message = message;
        return this;
    }

    public
    Object[] getPublishedObject() {
        return this.publishedObjects;
    }

    public
    PublicationError setPublishedObject(Object publishedObject) {
        this.publishedObjects = new Object[1];
        this.publishedObjects[0] = publishedObject;

        return this;
    }

    public
    PublicationError setPublishedObject(Object publishedObject1, Object publishedObject2) {
        this.publishedObjects = new Object[2];
        this.publishedObjects[0] = publishedObject1;
        this.publishedObjects[1] = publishedObject2;

        return this;
    }

    public
    PublicationError setPublishedObject(Object publishedObject1, Object publishedObject2, Object publishedObject3) {
        this.publishedObjects = new Object[3];
        this.publishedObjects[0] = publishedObject1;
        this.publishedObjects[1] = publishedObject2;
        this.publishedObjects[2] = publishedObject3;

        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public
    String toString() {
        String newLine = System.getProperty("line.separator");
        return "PublicationError{" +
               newLine +
               "\tcause=" + this.cause +
               newLine +
               "\tmessage='" + this.message + '\'' +
               newLine +
               "\tpublishedObject=" + Arrays.deepToString(this.publishedObjects) +
               '}';
    }
}
