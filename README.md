MessageBus
==========

The MessageBus is ane extremely light-weight message/event bus implementation that follows the publish/subscribe pattern and is based on the [MBassador](https://github.com/bennidi/mbassador) project. It is designed for ease of use and simplicity, and aims for **maximum performance** and **zero garbage** during message publication. At the core of this project is the use of the `single writer principle` as described by Nitsan Wakart on his [blog](http://psy-lob-saw.blogspot.com/2012/12/atomiclazyset-is-performance-win-for.html) and the fantastic [LMAX Disruptor](https://github.com/LMAX-Exchange/disruptor).
  
Using the MessageBus in your project is very easy.   
  1 Create an instance of the MessageBus (usually a singleton will do) `MessageBus bus = new MessageBus()`  
  2 Mark and configure your message handlers (the objects that will receive the messages) with `@Handler` notations  
  3 Register these via `bus.subscribe(listener)`  
  4 Send messages to these listeners via `bus.publish(message)` for synchronus publication, or `bus.publishAsync(message)` for asynchronous publication  
  5 (Optional) Free resources and threads via `bus.shutdown()` when you are finished (usually on application exit)  
  
  You're done! 

> Notes
  
The difference between the `sync` and `async` is that with `synchronous` publication, all of the logic and method calls occur on the same thread that calls it; while with an `asynchronous` publication, all of these actions occur on a separate thread. Please note that asynchronous publication is not in a guaranteed order.
  
  
`bus.shutdown()`. It is not necessary if exiting the JVM (which is most use-cases), but it is extremely useful in situations where you are reloading classes (ie: a webserver), as it will guarantee freeing all used resources and threads.
  
Table of contents:
+ [Features](#features)
+ [Usage](#usage)
+ [Installation](#installation)
+ [License](#license)

<h2 name="features">Features</h2>

> Annotations

|Annotation|Function|
|:-----|:-----|
|`@Handler`|Defines and customizes a message handler. Any well-formed method annotated with `@Handler` will cause instances of the defining class to be treated as message listeners|
|`@Listener`|Can be used to customize listener wide configuration like the used reference type|
|`@Synchronized`|Specifies that the handler/method will be accessed in a `synchronized` block|

> Delivers everything

Messages do not need to implement any interface and can be of any type. It is possible though to define an upper bound of the message type using generics. The class hierarchy of a message is considered during message delivery, such that handlers will also receive subtypes of the message type they consume for - e.g. a handler of Object.class receives everything. Messages that do not match any handler result in the publication of a `DeadMessage` object which wraps the original message. DeadMessage events can be handled by registering listeners that handle DeadMessage.

> Configurable reference types

By default, the MessageBus uses strong references for listeners. If the programmer wants to relieve the  need to explicitly unsubscribe listeners that are not used anymore and avoid memory-leaks, it is trivial to configure via `MessageBus.useStrongReferencesByDefault = false`. Using strong references is the fastest, most robust method for dispatching messages, however weak references are very comfortable in container managed environments where listeners are created and destroyed by frameworks, i.e. Spring, Guice etc. Just stuff everything into the message bus, it will ignore objects without message handlers and automatically clean-up orphaned weak references after the garbage collector has done its job. Strongly referenced listeners will stick around until explicitly unsubscribed.

> Custom error handling

Errors during message delivery are sent to all registered error handlers which can be added to the bus as necessary.


<h2>Usage</h2>

Handler definition (in any bean):

        // every message of type TestMessage or any subtype will be delivered to this handler
        @Handler
		public void handleTestMessage(TestMessage message) {
			// do something
		}

		// every message of type TestMessage or any subtype will be delivered to this handler
        @Handler
        public void handleTestMessage(TestMessage message) {
            // do something
        }

        // this handler will not accept subtypes of the TestMessage.
        @Handler(acceptSubtypes = false})
        public void handleNoSubTypes(TestMessage message) {
           //do something
        }

        // this handler will be accessed in a "syncrhonized" manner (only one thread at a time may access it)
        @Handler
        @Synchronized
        public void handleSyncrhonzied(TeastMessage message) {
            //do something
        }

        // configure a listener to be stored using strong/weak references
        @Listener(references = References.Strong)
        public class MessageListener{
            @Handler
            public void handleTestMessage(TestMessage message) {
                // do something
            }
        }


Creation of message bus and registration of listeners:

        // configure to use weak-references
        MessageBus.useStrongReferencesByDefault = false;

        // create as many instances as necessary (usually a singleton is best)
        MessageBus bus = new MessageBus();
        
        ListeningBean listener = new ListeningBean();
        
        // the listener will be registered using a weak-reference if not configured otherwise via @Listener
        bus.subscribe(listener);
        
        // this listener without handlers will be ignored
        bus.subscribe(new ClassWithoutAnyDefinedHandlers());
        
        // do stuff....
        
        
        // and when FINSIHED with the messagebus, to shutsdown all of the in-use threads and clean the data-structures
        bus.shutdown();
        


Message publication:

        TestMessage message = new TestMessage();
        TestMessage message2 = new TestMessage();
        TestMessage message3 = new TestMessage();
        TestMessage subMessage = new SubTestMessage();

        bus.publishAsync(message); //returns immediately, publication will continue asynchronously
        bus.publish(subMessage);   // will return after all the handlers have been invoked
        
        bus.publish(message, message2);   // will return after all the handlers have been invoked, but for two messages at the same time
        bus.publish(message, message2, message3);   // will return after all the handlers have been invoked, but for three messages 



<h4>We now release to maven!</h4> 

This project **includes** some utility classes, which are an extremely small subset of a much larger library; including only what is *necessary* for this particular project to function. Additionally this project is **kept in sync** with the utilities library, so "jar hell" is not an issue, and the latest release will always include the same utility files as all other projects in the dorkbox repository at that time.
  
  Please note that the utility classes have their source code included in the release, and eventually the entire utility library will be provided as a dorkbox repository.
```
    <dependency>
        <groupId>com.dorkbox</groupId>
        <artifactId>MessageBus</artifactId>
        <version>1.16</version>
    </dependency>
```


Or if you don't want to use Maven, you can access the files directly here:  
https://oss.sonatype.org/content/repositories/releases/com/dorkbox/MessageBus/

https://repo1.maven.org/maven2/org/slf4j/slf4j-api/  
https://repo1.maven.org/maven2/com/lmax/disruptor/  
https://repo1.maven.org/maven2/asm/asm/  

https://repo1.maven.org/maven2/com/esotericsoftware/kryo/  
https://repo1.maven.org/maven2/com/esotericsoftware/reflectasm/  

<h2>License</h2>

This project is © 2012 Benjamin Diedrichsen and © 2015 dorkbox llc, and is distributed under the terms of the Apache v2.0 License. See file "LICENSE" for further references.

