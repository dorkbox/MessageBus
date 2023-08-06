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
package dorkbox.messagebus;

import org.junit.Test;

import dorkbox.messageBus.common.ClassTree;
import dorkbox.messageBus.common.MultiClass;
import dorkbox.messagebus.common.AssertSupport;

public class MultiTreeTest extends AssertSupport {

    @Test
    public void testObjectTree() {
        ClassTree<Class<?>> tree = new ClassTree<Class<?>>();

        final MultiClass a = tree.get(String.class);
        final MultiClass b = tree.get(Object.class);
        final MultiClass c = tree.get(String.class, String.class);
        final MultiClass d = tree.get(Object.class, Object.class);
        final MultiClass e = tree.get(String.class, String.class, String.class);


        // we never can remove elements, unless we CLEAR the entire thing (usually at shutdown)
        assertNotNull(a);
        assertNotNull(b);
        assertNotNull(c);
        assertNotNull(d);
        assertNotNull(e);
    }
}
