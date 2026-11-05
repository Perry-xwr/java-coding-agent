package com.agent.tui.runtime;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded FIFO with interruptible backpressure. Critical events are never silently dropped. */
public final class UiMailbox implements AgentAdapter.EventSink,AutoCloseable {
    private final ArrayBlockingQueue<UiEvent.Task> queue;
    private final Thread owner;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicLong backpressure=new AtomicLong();
    public UiMailbox(int capacity,Thread owner){queue=new ArrayBlockingQueue<>(capacity);this.owner=owner;}
    @Override public void publish(UiEvent.Task event)throws InterruptedException {
        if(Thread.currentThread()==owner)throw new IllegalStateException("UI owner cannot block on its own mailbox");
        while(!closed.get()) {
            if(queue.offer(event,20,TimeUnit.MILLISECONDS))return;
            backpressure.incrementAndGet();
        }
        throw new InterruptedException("UI mailbox closed; delivery aborted during shutdown");
    }
    UiEvent.Task poll(){return queue.poll();}
    public int size(){return queue.size();}
    public long backpressureCount(){return backpressure.get();}
    @Override public void close(){closed.set(true);}
}
