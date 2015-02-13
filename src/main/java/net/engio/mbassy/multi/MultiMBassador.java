package net.engio.mbassy.multi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import net.engio.mbassy.multi.common.BoundedTransferQueue;
import net.engio.mbassy.multi.common.DisruptorThreadFactory;
import net.engio.mbassy.multi.common.LinkedTransferQueue;
import net.engio.mbassy.multi.disruptor.DeadMessage;
import net.engio.mbassy.multi.disruptor.DispatchFactory;
import net.engio.mbassy.multi.disruptor.DispatchHolder;
import net.engio.mbassy.multi.disruptor.DispatchProcessor;
import net.engio.mbassy.multi.disruptor.MessageHolder;
import net.engio.mbassy.multi.disruptor.PublicationExceptionHandler;
import net.engio.mbassy.multi.error.IPublicationErrorHandler;
import net.engio.mbassy.multi.error.PublicationError;
import net.engio.mbassy.multi.subscription.Subscription;
import net.engio.mbassy.multi.subscription.SubscriptionManager;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.WorkHandler;
import com.lmax.disruptor.WorkerPool;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

import dorkbox.util.objectPool.PoolableObject;

/**
 * The base class for all message bus implementations with support for asynchronous message dispatch
 *
 * @Author bennidi
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class MultiMBassador implements IMessageBus {

//    private static final DisruptorThreadFactory THREAD_FACTORY = new DisruptorThreadFactory("MPMC");

    // error handling is first-class functionality
    // this handler will receive all errors that occur during message dispatch or message handling
    private final List<IPublicationErrorHandler> errorHandlers = new ArrayList<IPublicationErrorHandler>();

    private final SubscriptionManager subscriptionManager;


    // any new thread will be 'NON-DAEMON', so that it will be forced to finish it's task before permitting the JVM to shut down
    private final ExecutorService dispatch_Executor;
    private final ExecutorService invoke_Executor;


//    private final TransferQueue<Object> dispatchQueue;
//    private final Queue<MessageHolder> dispatchQueue;
//    private final BlockingQueue<MessageHolder> dispatchQueue;
//    private final SynchronousQueue<MessageHolder> dispatchQueue;

//    private Queue<ObjectPoolHolder<MessageHolder>> mpmcArrayQueue;

    // all threads that are available for asynchronous message dispatching
//    private List<InterruptRunnable> invokeRunners;
//    private List<InterruptRunnable> dispatchRunners;

//    private dorkbox.util.objectPool.ObjectPool<MessageHolder> pool;


    // must be power of 2. For very high performance the ring buffer, and its contents, should fit in L3 CPU cache for exchanging between threads.
    private final int dispatch_RingBufferSize = 2;
//    private final int invoke_RingBufferSize = 2048;

    private final Disruptor<DispatchHolder> dispatch_Disruptor;
    private final RingBuffer<DispatchHolder> dispatch_RingBuffer;
    private final WorkerPool<DispatchHolder> dispatch_WorkerPool;

//    private final Disruptor<MessageHolder> invoke_Disruptor;
//    private final RingBuffer<MessageHolder> invoke_RingBuffer;
//    private final WorkerPool<MessageHolder> invoke_WorkerPool;



    public static class HolderPoolable implements PoolableObject<MessageHolder> {
        @Override
        public MessageHolder create() {
            return new MessageHolder();
        }
    }

    public MultiMBassador() {
        this(Runtime.getRuntime().availableProcessors());
    }

    private final ThreadLocal<DeadMessage> deadMessageCache = new ThreadLocal<DeadMessage>() {
        @Override
        protected DeadMessage initialValue()
        {
            return new DeadMessage(null);
        }
    };

    public MultiMBassador(int numberOfThreads) {
        if (numberOfThreads < 1) {
            numberOfThreads = 1; // at LEAST 1 threads
        }

//        this.objectQueue = new LinkedTransferQueue<MessageHolder>();
//        this.dispatchQueue = new LinkedTransferQueue<Object>();
//        this.dispatchQueue = new BoundedTransferQueue<MessageHolder>(numberOfThreads);
//        this.dispatchQueue = new MpmcArrayQueue<MessageHolder>(Pow2.roundToPowerOfTwo(numberOfThreads/2));
//        this.dispatchQueue = new PTLQueue<MessageHolder>(Pow2.roundToPowerOfTwo(numberOfThreads/2));
//        this.dispatchQueue = new ArrayBlockingQueue<MessageHolder>(4);
//        this.dispatchQueue = new SynchronousQueue<MessageHolder>();
//        this.dispatchQueue = new LinkedBlockingQueue<MessageHolder>(Pow2.roundToPowerOfTwo(numberOfThreads));


        int dispatchSize = 2;
        this.dispatch_Executor = new ThreadPoolExecutor(dispatchSize, dispatchSize, 1L, TimeUnit.MINUTES,
                                                        new LinkedTransferQueue<Runnable>(),
                                                        new DisruptorThreadFactory("MB_Dispatch"));

        this.invoke_Executor = new ThreadPoolExecutor(numberOfThreads, numberOfThreads*2, 1L, TimeUnit.MINUTES,
                                                      new BoundedTransferQueue<Runnable>(numberOfThreads),
                                                      new DisruptorThreadFactory("MB_Invoke"));



        this.subscriptionManager = new SubscriptionManager();
//        this.mpmcArrayQueue = new MpmcArrayQueue<ObjectPoolHolder<MessageHolder>>(numberOfThreads*2);
//        this.pool = ObjectPoolFactory.create(new HolderPoolable(), numberOfThreads*2);



//        int dispatchSize = Pow2.roundToPowerOfTwo(numberOfThreads*2);
//        this.dispatchRunners = new ArrayList<InterruptRunnable>(dispatchSize);
//        DisruptorThreadFactory dispatchThreadFactory = new DisruptorThreadFactory("MB_Dispatch");
//        for (int i = 0; i < dispatchSize; i++) {
//            // each thread will run forever and process incoming message publication requests
//            InterruptRunnable runnable = new InterruptRunnable() {
//                private final ThreadLocal<DeadMessage> deadMessageCache = new ThreadLocal<DeadMessage>() {
//                    @Override
//                    protected DeadMessage initialValue()
//                    {
//                        return new DeadMessage(null);
//                    }
//                };
//
//
//                @Override
//                public void run() {
//                    final MultiMBassador mbassador = MultiMBassador.this;
//                    SubscriptionManager manager = mbassador.subscriptionManager;
//                    final TransferQueue<Object> IN_queue = mbassador.dispatchQueue;
////                    final Queue<MessageHolder> OUT_queue = mbassador.invokeQueue;
//
//                    Object message = null;
////                    int counter = 200;
//                    try {
//                        while (this.running) {
//                            message = IN_queue.take();
////                            value = IN_queue.poll();
////                            if (value != null) {
//                                Class<?> messageClass = message.getClass();
//
//                                Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass);
//
//                                try {
//                                    boolean empty = subscriptions.isEmpty();
//                                    if (empty) {
//
//                                        // Dead Event
//                                        subscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
//
//                                        DeadMessage deadMessage = this.deadMessageCache.get();
//                                        deadMessage.relatedMessages[0] = message;
//                                        message = deadMessage;
//                                        empty = subscriptions.isEmpty();
//                                    }
//
//                                    if (!empty) {
//                                        for (Subscription sub : subscriptions) {
////                                          boolean handled = false;
////                                          if (sub.isVarArg()) {
////                                              // messageClass will NEVER be an array to begin with, since that will call the multi-arg method
////                                              if (vararg == null) {
////                                                  // messy, but the ONLY way to do it.
////                                                  vararg = (Object[]) Array.newInstance(messageClass, 1);
////                                                  vararg[0] = message;
//      //
////                                                  Object[] newInstance =  new Object[1];
////                                                  newInstance[0] = vararg;
////                                                  vararg = newInstance;
////                                              }
//      //
////                                              handled = true;
////                                              sub.publishToSubscription(mbassador, vararg);
////                                          }
//      //
////                                          if (!handled) {
//                                              sub.publishToSubscription(mbassador, message);
////                                          }
//                                      }
//                                    }
////                                    counter = 200;
//                                } catch (Throwable e) {
//                                    mbassador.handlePublicationError(new PublicationError().setMessage("Error during publication of message").setCause(e).setPublishedObject(message));
//                                }
////                            } else {
////                                if (counter > 100) {
////                                    --counter;
////                                } else if (counter > 0) {
////                                    --counter;
////                                    Thread.yield();
////                                } else {
////                                    LockSupport.parkNanos(1L);
////                                }
////                            }
//                        }
//                    } catch (InterruptedException e) {
//                        Thread.currentThread().interrupt();
//                        return;
//                    }
//                }
//            };
//
//            Thread runner = dispatchThreadFactory.newThread(runnable);
//            this.dispatchRunners.add(runnable);
//            runner.start();
//        }
//////////////////////////////////////////////////////

//      this.invokeRunners = new ArrayList<InterruptRunnable>(numberOfThreads*2);
//      DisruptorThreadFactory invokerThreadFactory = new DisruptorThreadFactory("MB_Invoke");
//      for (int i = 0; i < numberOfThreads; i++) {
//          // each thread will run forever and process incoming message publication requests
//          InterruptRunnable runnable = new InterruptRunnable() {
//              @Override
//              public void run() {
//
//              }
//          };
//      }



//        this.invokeRunners = new ArrayList<InterruptRunnable>(numberOfThreads*2);
//        DisruptorThreadFactory invokerThreadFactory = new DisruptorThreadFactory("MB_Invoke");
//        for (int i = 0; i < numberOfThreads; i++) {
//            // each thread will run forever and process incoming message publication requests
//            InterruptRunnable runnable = new InterruptRunnable() {
//                @Override
//                public void run() {
//                    final int DEFAULT_RETRIES = 200;
//                    final MultiMBassador mbassador = MultiMBassador.this;
//                    final Queue<MessageHolder> queue = mbassador.invokeQueue;
////                    final SubscriptionManager manager = mbassador.subscriptionManager;
////                    final ObjectPool<MessageHolder> pool2 = mbassador.pool;
//
//                    int counter = DEFAULT_RETRIES;
////                    ObjectPoolHolder<MessageHolder> holder = null;
//                    MessageHolder value = null;
//
//                    while (this.running) {
//                        value = queue.poll();
//                        if (value != null) {
//                            // off to be executed
//
//                            Collection<Subscription> subscriptions = value.subscriptions;
//                            Object message = value.message1;
////                            Class<? extends Object> messageClass = message.getClass();
//
////                            if (messageClass.equals(DeadMessage.class)) {
////                                for (Subscription sub : subscriptions) {
////                                    sub.publishToSubscription(mbassador, message);
////                                }
////                            } else {
////                                Object[] vararg = null;
//
//                                for (Subscription sub : subscriptions) {
////                                    boolean handled = false;
////                                    if (sub.isVarArg()) {
////                                        // messageClass will NEVER be an array to begin with, since that will call the multi-arg method
////                                        if (vararg == null) {
////                                            // messy, but the ONLY way to do it.
////                                            vararg = (Object[]) Array.newInstance(messageClass, 1);
////                                            vararg[0] = message;
////
////                                            Object[] newInstance =  new Object[1];
////                                            newInstance[0] = vararg;
////                                            vararg = newInstance;
////                                        }
////
////                                        handled = true;
////                                        sub.publishToSubscription(mbassador, vararg);
////                                    }
////
////                                    if (!handled) {
//                                        sub.publishToSubscription(mbassador, message);
////                                    }
//                                }
////                            }
//
////                            pool2.release(holder);
//
//                            counter = DEFAULT_RETRIES;
//                        } else {
//                            if (counter > 100) {
//                                --counter;
//                            } else if (counter > 0) {
//                                --counter;
//                                Thread.yield();
//                            } else {
//                                LockSupport.parkNanos(1L);
//                            }
//                        }
//
////                            handlePublicationError(new PublicationError(t, "Error in asynchronous dispatch",holder));
//                    }
//                }
//            };
//
//            Thread runner = invokerThreadFactory.newThread(runnable);
//            this.invokeRunners.add(runnable);
//            runner.start();
//        }



















////////////////////////////
        PublicationExceptionHandler loggingExceptionHandler = new PublicationExceptionHandler(this);

        this.dispatch_Disruptor = new Disruptor<DispatchHolder>(new DispatchFactory(), this.dispatch_RingBufferSize, this.dispatch_Executor,
                                                                ProducerType.MULTI, new SleepingWaitStrategy());
//        this.invoke_Disruptor = new Disruptor<MessageHolder>(new InvokeFactory(), this.invoke_RingBufferSize, this.invoke_Executor,
//                                                             ProducerType.MULTI, new SleepingWaitStrategy());


        this.dispatch_RingBuffer = this.dispatch_Disruptor.getRingBuffer();
//        this.invoke_RingBuffer = this.invoke_Disruptor.getRingBuffer();

        // not too many handlers, so we don't contend the locks in the subscription manager
        WorkHandler<DispatchHolder> dispatchHandlers[] = new DispatchProcessor[dispatchSize];
        for (int i = 0; i < dispatchHandlers.length; i++) {
            dispatchHandlers[i] = new DispatchProcessor(i, dispatchHandlers.length, this.invoke_Executor);
        }

//        WorkHandler<MessageHolder> invokeHandlers[] = new InvokeProcessor[numberOfThreads];
//        for (int i = 0; i < invokeHandlers.length; i++) {
//            invokeHandlers[i] = new InvokeProcessor(this);
//        }
//
//        this.dispatch_Disruptor.handleEventsWith(dispatchHandlers);
        this.dispatch_WorkerPool = new WorkerPool<DispatchHolder>(this.dispatch_RingBuffer,
                                                                  this.dispatch_RingBuffer.newBarrier(),
                                                                  loggingExceptionHandler,
                                                                  dispatchHandlers);
//
        this.dispatch_RingBuffer.addGatingSequences(this.dispatch_WorkerPool.getWorkerSequences());
/////////////////////////////////

//
//        this.runners = new ArrayList<InterruptRunnable>(numberOfThreads);
//        for (int i = 0; i < numberOfThreads; i++) {
//            // each thread will run forever and process incoming message publication requests
//            InterruptRunnable runnable = new InterruptRunnable() {
//                @Override
//                public void run() {
//                    final int DEFAULT_RETRIES = 200;
//                    final MultiMBassador mbassador = MultiMBassador.this;
//                    final Queue<ObjectPoolHolder<MessageHolder>> queue = mbassador.mpmcArrayQueue;
//                    final ObjectPool<MessageHolder> pool2 = mbassador.pool;
//
//                    int counter = DEFAULT_RETRIES;
//                    ObjectPoolHolder<MessageHolder> holder = null;
//                    MessageHolder value;
//
//                    while (this.running) {
//                        holder = queue.poll();
//                        if (holder != null) {
//                            // sends off to an executor
//                            value = holder.getValue();
//                            Collection<Subscription> subscriptions = value.subscriptionsHolder;
//                            Object message = value.message1;
//                            Class<? extends Object> messageClass = message.getClass();
//
//                            if (messageClass.equals(DeadMessage.class)) {
//                                for (Subscription sub : subscriptions) {
//                                    sub.publishToSubscription(mbassador, message);
//                                }
//                            } else {
//                                Object[] vararg = null;
//
//                                for (Subscription sub : subscriptions) {
//                                    boolean handled = false;
//                                    if (sub.isVarArg()) {
//                                        // messageClass will NEVER be an array to begin with, since that will call the multi-arg method
//                                        if (vararg == null) {
//                                            // messy, but the ONLY way to do it.
//                                            vararg = (Object[]) Array.newInstance(messageClass, 1);
//                                            vararg[0] = message;
//
//                                            Object[] newInstance =  new Object[1];
//                                            newInstance[0] = vararg;
//                                            vararg = newInstance;
//                                        }
//
//                                        handled = true;
//                                        sub.publishToSubscription(mbassador, vararg);
//                                    }
//
//                                    if (!handled) {
//                                        sub.publishToSubscription(mbassador, message);
//                                    }
//                                }
//                            }
//
//                            pool2.release(holder);
//                            counter = DEFAULT_RETRIES;
//                        } else {
//                            if (counter > 100) {
//                                --counter;
//                            } else if (counter > 0) {
//                                --counter;
//                                Thread.yield();
//                            } else {
//                                LockSupport.parkNanos(1L);
//                                counter = DEFAULT_RETRIES;
//                            }
//                        }
//
////                            handlePublicationError(new PublicationError(t, "Error in asynchronous dispatch",holder));
//                    }
//                }
//            };
//            Thread runner = THREAD_FACTORY.newThread(runnable);
//            this.runners.add(runnable);
//            runner.start();
//        }
    }

    public final MultiMBassador start() {
        this.dispatch_WorkerPool.start(this.dispatch_Executor);
//        this.invoke_Disruptor.start();
        this.dispatch_Disruptor.start();
        return this;
    }


    @Override
    public final void addErrorHandler(IPublicationErrorHandler handler) {
        synchronized (this.errorHandlers) {
            this.errorHandlers.add(handler);
        }
    }

    @Override
    public final void handlePublicationError(PublicationError error) {
        for (IPublicationErrorHandler errorHandler : this.errorHandlers) {
            errorHandler.handleError(error);
        }
    }

    @Override
    public void unsubscribe(Object listener) {
        this.subscriptionManager.unsubscribe(listener);
    }


    @Override
    public void subscribe(Object listener) {
        this.subscriptionManager.subscribe(listener);
    }

    @Override
    public boolean hasPendingMessages() {
        return this.pendingMessages.get() > 0L;
//        return this.dispatch_RingBuffer.remainingCapacity() < this.dispatch_RingBufferSize;
//        return !this.dispatchQueue.isEmpty();
    }

    @Override
    public void shutdown() {
//        for (InterruptRunnable runnable : this.dispatchRunners) {
//            runnable.stop();
//        }

//        for (InterruptRunnable runnable : this.invokeRunners) {
//            runnable.stop();
//        }

        this.dispatch_Disruptor.shutdown();
        this.dispatch_Executor.shutdown();
        this.invoke_Executor.shutdown();
    }


    @Override
    public void publish(Object message) {
//        Class<?> messageClass = message.getClass();
//
//        SubscriptionManager manager = this.subscriptionManager;
//        Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass);
//
//        try {
//            if (subscriptions.isEmpty()) {
//                // Dead Event
//                subscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
//
//                DeadMessage deadMessage = new DeadMessage(message);
//
//                for (Subscription sub : subscriptions) {
//                    sub.publishToSubscription(this, deadMessage);
//                }
//            } else {
//                Object[] vararg = null;
//
//                for (Subscription sub : subscriptions) {
//                    boolean handled = false;
//                    if (sub.isVarArg()) {
//                        // messageClass will NEVER be an array to begin with, since that will call the multi-arg method
//                        if (vararg == null) {
//                            // messy, but the ONLY way to do it.
//                            vararg = (Object[]) Array.newInstance(messageClass, 1);
//                            vararg[0] = message;
//
//                            Object[] newInstance =  new Object[1];
//                            newInstance[0] = vararg;
//                            vararg = newInstance;
//                        }
//
//                        handled = true;
//                        sub.publishToSubscription(this, vararg);
//                    }
//
//                    if (!handled) {
//                        sub.publishToSubscription(this, message);
//                    }
//                }
//            }
//        } catch (Throwable e) {
//            handlePublicationError(new PublicationError()
//                    .setMessage("Error during publication of message")
//                    .setCause(e)
//                    .setPublishedObject(message));
//        }
    }


    @Override
    public void publish(Object message1, Object message2) {
//        try {
//            Class<?> messageClass1 = message1.getClass();
//            Class<?> messageClass2 = message2.getClass();
//
//            SubscriptionManager manager = this.subscriptionManager;
//            Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass1, messageClass2);
//
//            if (subscriptions == null || subscriptions.isEmpty()) {
//                // Dead Event
//                subscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
//
//                DeadMessage deadMessage = new DeadMessage(message1, message2);
//
//                for (Subscription sub : subscriptions) {
//                    sub.publishToSubscription(this, deadMessage);
//                }
//            } else {
//                Object[] vararg = null;
//
//                for (Subscription sub : subscriptions) {
//                    boolean handled = false;
//                    if (sub.isVarArg()) {
//                        Class<?> class1 = message1.getClass();
//                        Class<?> class2 = message2.getClass();
//                        if (!class1.isArray() && class1 == class2) {
//                            if (vararg == null) {
//                                // messy, but the ONLY way to do it.
//                                vararg = (Object[]) Array.newInstance(class1, 2);
//                                vararg[0] = message1;
//                                vararg[1] = message2;
//
//                                Object[] newInstance =  (Object[]) Array.newInstance(vararg.getClass(), 1);
//                                newInstance[0] = vararg;
//                                vararg = newInstance;
//                            }
//
//                            handled = true;
//                            sub.publishToSubscription(this, vararg);
//                        }
//                    }
//
//                    if (!handled) {
//                        sub.publishToSubscription(this, message1, message2);
//                    }
//                }
//
//                // if the message did not have any listener/handler accept it
//                if (subscriptions.isEmpty()) {
//                    // cannot have DeadMessage published to this, so no extra check necessary
//                    // Dead Event
//                    subscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
//                    DeadMessage deadMessage = new DeadMessage(message1, message2);
//
//                    for (Subscription sub : subscriptions) {
//                        sub.publishToSubscription(this, deadMessage);
//                    }
//                }
//            }
//        } catch (Throwable e) {
//            handlePublicationError(new PublicationError()
//                    .setMessage("Error during publication of message")
//                    .setCause(e)
//                    .setPublishedObject(message1, message2));
//        }
    }

    @Override
    public void publish(Object message1, Object message2, Object message3) {
//        try {
//            Class<?> messageClass1 = message1.getClass();
//            Class<?> messageClass2 = message2.getClass();
//            Class<?> messageClass3 = message3.getClass();
//
//            SubscriptionManager manager = this.subscriptionManager;
//            Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass1, messageClass2, messageClass3);
//
//            if (subscriptions == null || subscriptions.isEmpty()) {
//                // Dead Event
//                subscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
//                DeadMessage deadMessage = new DeadMessage(message1, message2, message3);
//
//                for (Subscription sub : subscriptions) {
//                    sub.publishToSubscription(this, deadMessage);
//                }
//            } else {
//                Object[] vararg = null;
//
//                for (Subscription sub : subscriptions) {
//                    boolean handled = false;
//                    if (sub.isVarArg()) {
//                        Class<?> class1 = message1.getClass();
//                        Class<?> class2 = message2.getClass();
//                        Class<?> class3 = message3.getClass();
//                        if (!class1.isArray() && class1 == class2 && class2 == class3) {
//                            // messy, but the ONLY way to do it.
//                            if (vararg == null) {
//                                vararg = (Object[]) Array.newInstance(class1, 3);
//                                vararg[0] = message1;
//                                vararg[1] = message2;
//                                vararg[2] = message3;
//
//                                Object[] newInstance =  (Object[]) Array.newInstance(vararg.getClass(), 1);
//                                newInstance[0] = vararg;
//                                vararg = newInstance;
//                            }
//
//                            handled = true;
//                            sub.publishToSubscription(this, vararg);
//                        }
//                    }
//
//                    if (!handled) {
//                        sub.publishToSubscription(this, message1, message2, message3);
//                    }
//                }
//
//                // if the message did not have any listener/handler accept it
//                if (subscriptions.isEmpty()) {
//                    // cannot have DeadMessage published to this, so no extra check necessary
//                    // Dead Event
//                    subscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
//                    DeadMessage deadMessage = new DeadMessage(message1, message2, message3);
//
//                    for (Subscription sub : subscriptions) {
//                        sub.publishToSubscription(this, deadMessage);
//                    }
//
//                    // cleanup
//                    deadMessage = null;
//                }
//            }
//        } catch (Throwable e) {
//            handlePublicationError(new PublicationError()
//                    .setMessage("Error during publication of message")
//                    .setCause(e)
//                    .setPublishedObject(message1, message2, message3));
//        }
    }

    @Override
    public void publish(Object... messages) {
//        try {
//            // cannot have DeadMessage published to this!
//            int size = messages.length;
//            boolean allSameType = true;
//
//            Class<?>[] messageClasses = new Class[size];
//            Class<?> first = null;
//            if (size > 0) {
//                first = messageClasses[0] = messages[0].getClass();
//            }
//
//            for (int i=1;i<size;i++) {
//                messageClasses[i] = messages[i].getClass();
//                if (first != messageClasses[i]) {
//                    allSameType = false;
//                }
//            }
//
//            SubscriptionManager manager = this.subscriptionManager;
//            Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClasses);
//
//            if (subscriptions == null || subscriptions.isEmpty()) {
//                // Dead Event
//                subscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
//                DeadMessage deadMessage = new DeadMessage(messages);
//
//                for (Subscription sub : subscriptions) {
//                    sub.publishToSubscription(this, deadMessage);
//                }
//            } else {
//                Object[] vararg = null;
//
//                for (Subscription sub : subscriptions) {
//                    boolean handled = false;
//                    if (first != null && allSameType && sub.isVarArg()) {
//                        if (vararg == null) {
//                            // messy, but the ONLY way to do it.
//                            vararg = (Object[]) Array.newInstance(first, size);
//
//                            for (int i=0;i<size;i++) {
//                                vararg[i] = messages[i];
//                            }
//
//                            Object[] newInstance =  (Object[]) Array.newInstance(vararg.getClass(), 1);
//                            newInstance[0] = vararg;
//                            vararg = newInstance;
//                        }
//
//                        handled = true;
//                        sub.publishToSubscription(this, vararg);
//                    }
//
//                    if (!handled) {
//                        sub.publishToSubscription(this, messages);
//                    }
//                }
//
//                // if the message did not have any listener/handler accept it
//                if (subscriptions.isEmpty()) {
//                    // cannot have DeadMessage published to this, so no extra check necessary
//                    // Dead Event
//
//                    subscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
//                    DeadMessage deadMessage = new DeadMessage(messages);
//
//                    for (Subscription sub : subscriptions) {
//                        sub.publishToSubscription(this, deadMessage);
//                    }
//                }
//            }
//        } catch (Throwable e) {
//            handlePublicationError(new PublicationError()
//                    .setMessage("Error during publication of message")
//                    .setCause(e)
//                    .setPublishedObject(messages));
//        }
    }

    private final AtomicLong pendingMessages = new AtomicLong(0);

    @Override
    public void publishAsync(final Object message) {
        if (message != null) {
            // put this on the disruptor ring buffer
            final RingBuffer<DispatchHolder> ringBuffer = this.dispatch_RingBuffer;

            // setup the job
            final long seq = ringBuffer.next();
            try {
                Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
//                        System.err.println("invoke");
                        Object localMessage = message;

                        Class<?> messageClass = localMessage.getClass();

                        SubscriptionManager subscriptionManager = MultiMBassador.this.subscriptionManager;
                        Collection<Subscription> subscriptions = subscriptionManager.getSubscriptionsByMessageType(messageClass);

                        boolean empty = subscriptions.isEmpty();
                        if (empty) {
                            // Dead Event
                            subscriptions = subscriptionManager.getSubscriptionsByMessageType(DeadMessage.class);

//                            DeadMessage deadMessage = MultiMBassador.this.deadMessageCache.get();
                            localMessage = new DeadMessage(message);
                            empty = subscriptions.isEmpty();
                        }

                        if (!empty) {
                            for (Subscription sub : subscriptions) {
//                                  boolean handled = false;
//                                  if (sub.isVarArg()) {
//                                      // messageClass will NEVER be an array to begin with, since that will call the multi-arg method
//                                      if (vararg == null) {
//                                          // messy, but the ONLY way to do it.
//                                          vararg = (Object[]) Array.newInstance(messageClass, 1);
//                                          vararg[0] = message;
//
//                                          Object[] newInstance =  new Object[1];
//                                          newInstance[0] = vararg;
//                                          vararg = newInstance;
//                                      }
//
//                                      handled = true;
//                                      sub.publishToSubscription(mbassador, vararg);
//                                  }
//
//                                  if (!handled) {
                                  sub.publishToSubscription(MultiMBassador.this, localMessage);
//                                  }
                            }
                        }

                        MultiMBassador.this.pendingMessages.getAndDecrement();
                    }
                };


                DispatchHolder eventJob = ringBuffer.get(seq);
                eventJob.runnable = runnable;
            } catch (Throwable e) {
                handlePublicationError(new PublicationError()
                                            .setMessage("Error while adding an asynchronous message")
                                            .setCause(e)
                                            .setPublishedObject(message));
            } finally {
                // always publish the job
                ringBuffer.publish(seq);
            }
            this.pendingMessages.getAndIncrement();
//            System.err.println("adding " + this.pendingMessages.getAndIncrement());

//            MessageHolder messageHolder = new MessageHolder();
//            messageHolder.messageType = MessageType.ONE;
//            messageHolder.message1 = message;



//            try {
//                this.dispatchQueue.transfer(message);
//
////            int counter = 200;
////            while (!this.dispatchQueue.offer(messageHolder)) {
////                if (counter > 100) {
////                    --counter;
////                } else if (counter > 0) {
////                    --counter;
////                    Thread.yield();
////                } else {
////                    LockSupport.parkNanos(1L);
////                }
////            }
//
//
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//                // log.error(e);
//
//                handlePublicationError(new PublicationError()
//                .setMessage("Error while adding an asynchronous message")
//                .setCause(e)
//                .setPublishedObject(message));
//            }
        }
    }

    @Override
    public void publishAsync(Object message1, Object message2) {
//        // put this on the disruptor ring buffer
//        final RingBuffer<MessageHolder> ringBuffer = this.ringBuffer;
//
//        // setup the job
//        final long seq = ringBuffer.next();
//        try {
//            MessageHolder eventJob = ringBuffer.get(seq);
//            eventJob.messageType = MessageType.TWO;
//            eventJob.message1 = message1;
//            eventJob.message2 = message2;
//        } catch (Exception e) {
//            handlePublicationError(new PublicationError()
//                                        .setMessage("Error while adding an asynchronous message")
//                                        .setCause(e)
//                                        .setPublishedObject(message1, message2));
//        } finally {
//            // always publish the job
//            ringBuffer.publish(seq);
//        }
    }

    @Override
    public void publishAsync(Object message1, Object message2, Object message3) {
//        // put this on the disruptor ring buffer
//        final RingBuffer<MessageHolder> ringBuffer = this.ringBuffer;
//
//        // setup the job
//        final long seq = ringBuffer.next();
//        try {
//            MessageHolder eventJob = ringBuffer.get(seq);
//            eventJob.messageType = MessageType.THREE;
//            eventJob.message1 = message1;
//            eventJob.message2 = message2;
//            eventJob.message3 = message3;
//        } catch (Exception e) {
//            handlePublicationError(new PublicationError()
//            .setMessage("Error while adding an asynchronous message")
//            .setCause(e)
//            .setPublishedObject(new Object[] {message1, message2, message3}));
//        } finally {
//            // always publish the job
//            ringBuffer.publish(seq);
//        }
    }

    @Override
    public void publishAsync(Object... messages) {
//        // put this on the disruptor ring buffer
//        final RingBuffer<MessageHolder> ringBuffer = this.ringBuffer;
//
//        // setup the job
//        final long seq = ringBuffer.next();
//        try {
//            MessageHolder eventJob = ringBuffer.get(seq);
//            eventJob.messageType = MessageType.ARRAY;
//            eventJob.messages = messages;
//        } catch (Exception e) {
//            handlePublicationError(new PublicationError()
//                    .setMessage("Error while adding an asynchronous message")
//                    .setCause(e)
//                    .setPublishedObject(messages));
//        } finally {
//            // always publish the job
//            ringBuffer.publish(seq);
//        }
    }

    @Override
    public void publishAsync(long timeout, TimeUnit unit, Object message) {
//        // put this on the disruptor ring buffer
//        final RingBuffer<MessageHolder> ringBuffer = this.ringBuffer;
//        final long expireTimestamp = TimeUnit.MILLISECONDS.convert(timeout, unit) + System.currentTimeMillis();
//
//        // Inserts the specified element into this buffer, waiting up to the specified wait time if necessary for space
//        // to become available.
//        while (!ringBuffer.hasAvailableCapacity(1)) {
//            LockSupport.parkNanos(10L);
//            if (expireTimestamp <= System.currentTimeMillis()) {
//                handlePublicationError(new PublicationError()
//                                            .setMessage("Error while adding an asynchronous message")
//                                            .setCause(new Exception("Timeout"))
//                                            .setPublishedObject(message));
//                return;
//            }
//        }
//
//        // setup the job
//        final long seq = ringBuffer.next();
//        try {
//            MessageHolder eventJob = ringBuffer.get(seq);
//            eventJob.messageType = MessageType.ONE;
//            eventJob.message1 = message;
//        } catch (Exception e) {
//            handlePublicationError(new PublicationError()
//                                        .setMessage("Error while adding an asynchronous message")
//                                        .setCause(e)
//                                        .setPublishedObject(message));
//        } finally {
//            // always publish the job
//            ringBuffer.publish(seq);
//        }
    }
    @Override
    public void publishAsync(long timeout, TimeUnit unit, Object message1, Object message2) {
//        // put this on the disruptor ring buffer
//        final RingBuffer<MessageHolder> ringBuffer = this.ringBuffer;
//        final long expireTimestamp = TimeUnit.MILLISECONDS.convert(timeout, unit) + System.currentTimeMillis();
//
//        // Inserts the specified element into this buffer, waiting up to the specified wait time if necessary for space
//        // to become available.
//        while (!ringBuffer.hasAvailableCapacity(1)) {
//            LockSupport.parkNanos(10L);
//            if (expireTimestamp <= System.currentTimeMillis()) {
//                handlePublicationError(new PublicationError()
//                        .setMessage("Error while adding an asynchronous message")
//                        .setCause(new Exception("Timeout"))
//                        .setPublishedObject(message1, message2));
//                return;
//            }
//        }
//
//        // setup the job
//        final long seq = ringBuffer.next();
//        try {
//            MessageHolder eventJob = ringBuffer.get(seq);
//            eventJob.messageType = MessageType.TWO;
//            eventJob.message1 = message1;
//            eventJob.message2 = message2;
//        } catch (Exception e) {
//            handlePublicationError(new PublicationError()
//                    .setMessage("Error while adding an asynchronous message")
//                    .setCause(e)
//                    .setPublishedObject(message1, message2));
//        } finally {
//            // always publish the job
//            ringBuffer.publish(seq);
//        }
    }
    @Override
    public void publishAsync(long timeout, TimeUnit unit, Object message1, Object message2, Object message3) {
//        // put this on the disruptor ring buffer
//        final RingBuffer<MessageHolder> ringBuffer = this.ringBuffer;
//        final long expireTimestamp = TimeUnit.MILLISECONDS.convert(timeout, unit) + System.currentTimeMillis();
//
//        // Inserts the specified element into this buffer, waiting up to the specified wait time if necessary for space
//        // to become available.
//        while (!ringBuffer.hasAvailableCapacity(1)) {
//            LockSupport.parkNanos(10L);
//            if (expireTimestamp <= System.currentTimeMillis()) {
//                handlePublicationError(new PublicationError()
//                    .setMessage("Error while adding an asynchronous message")
//                    .setCause(new Exception("Timeout"))
//                    .setPublishedObject(message1, message2, message3));
//                return;
//            }
//        }
//
//        // setup the job
//        final long seq = ringBuffer.next();
//        try {
//            MessageHolder eventJob = ringBuffer.get(seq);
//            eventJob.messageType = MessageType.THREE;
//            eventJob.message1 = message1;
//            eventJob.message2 = message2;
//            eventJob.message3 = message3;
//        } catch (Exception e) {
//            handlePublicationError(new PublicationError()
//                    .setMessage("Error while adding an asynchronous message")
//                    .setCause(e)
//                    .setPublishedObject(message1, message2, message3));
//        } finally {
//            // always publish the job
//            ringBuffer.publish(seq);
//        }
    }

    @Override
    public void publishAsync(long timeout, TimeUnit unit, Object... messages) {
//        // put this on the disruptor ring buffer
//        final RingBuffer<MessageHolder> ringBuffer = this.ringBuffer;
//        final long expireTimestamp = TimeUnit.MILLISECONDS.convert(timeout, unit) + System.currentTimeMillis();
//
//        // Inserts the specified element into this buffer, waiting up to the specified wait time if necessary for space
//        // to become available.
//        while (!ringBuffer.hasAvailableCapacity(1)) {
//            LockSupport.parkNanos(10L);
//            if (expireTimestamp <= System.currentTimeMillis()) {
//                handlePublicationError(new PublicationError()
//                        .setMessage("Error while adding an asynchronous message")
//                        .setCause(new Exception("Timeout"))
//                        .setPublishedObject(messages));
//                return;
//            }
//        }
//
//        // setup the job
//        final long seq = ringBuffer.next();
//        try {
//            MessageHolder eventJob = ringBuffer.get(seq);
//            eventJob.messageType = MessageType.ARRAY;
//            eventJob.messages = messages;
//        } catch (Exception e) {
//            handlePublicationError(new PublicationError()
//                    .setMessage("Error while adding an asynchronous message")
//                    .setCause(e)
//                    .setPublishedObject(messages));
//        } finally {
//            // always publish the job
//            ringBuffer.publish(seq);
//        }
    }
}
