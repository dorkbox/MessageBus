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

/**
 * The dead message event is published whenever no message handlers could be found for a given message publication.
 *
 * @author bennidi
 *         Date: 1/18/13
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public final
class DeadMessage {

    private final Object[] relatedMessages;

    public
    DeadMessage(Object message) {
        this.relatedMessages = new Object[1];
        this.relatedMessages[0] = message;
    }

    public
    DeadMessage(Object message1, Object message2) {
        this.relatedMessages = new Object[2];
        this.relatedMessages[0] = message1;
        this.relatedMessages[1] = message2;
    }

    public
    DeadMessage(Object message1, Object message2, Object message3) {
        this.relatedMessages = new Object[3];
        this.relatedMessages[0] = message1;
        this.relatedMessages[1] = message2;
        this.relatedMessages[2] = message3;
    }

    public
    Object[] getMessages() {
        return this.relatedMessages;
    }
}
