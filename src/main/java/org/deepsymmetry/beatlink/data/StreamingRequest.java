package org.deepsymmetry.beatlink.data;

import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongUnaryOperator;

/** One shared request per asset. Failure never becomes a cached null success. */
final class StreamingRequest<T> {
    interface Sender { void send(int attempt) throws Exception; }
    interface Reporter { void report(int attempt, long delay, Exception failure); }
    final CompletableFuture<T> future = new CompletableFuture<>();
    private final ScheduledExecutorService scheduler;
    private final BooleanSupplier current;
    private final Sender sender;
    private final Reporter reporter;
    private final LongUnaryOperator delays;
    private ScheduledFuture<?> scheduled;
    private int attempts;
    private final long budgetNanos;
    private long startedNanos;

    StreamingRequest(ScheduledExecutorService scheduler, BooleanSupplier current, Sender sender,
                     Reporter reporter, LongUnaryOperator delays) {
        this(scheduler, current, sender, reporter, delays, 60000);
    }
    StreamingRequest(ScheduledExecutorService scheduler, BooleanSupplier current, Sender sender,
                     Reporter reporter, LongUnaryOperator delays, long budgetMillis) {
        if (budgetMillis <= 0) throw new IllegalArgumentException("Positive retry budget required");
        this.budgetNanos=TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        this.scheduler=scheduler; this.current=current; this.sender=sender;
        this.reporter=reporter; this.delays=delays;
    }
    static long delay(int attempt) {
        return attempt <= 3 ? 1500 : Math.min(30000, 3000L << Math.min(4, attempt-4));
    }
    synchronized void start() {
        if (scheduled == null && !future.isDone()) {
            startedNanos=System.nanoTime();
            scheduled=scheduler.schedule(this::attempt,0,TimeUnit.MILLISECONDS);
        }
    }
    private synchronized void attempt() {
        if(future.isDone())return;
        try {
            if(!current.getAsBoolean()){cancel();return;}
            long remaining=budgetNanos-(System.nanoTime()-startedNanos);
            if (remaining <= 0) {
                cancel(); // Retained in the asset cache: callers cannot start a new budget.
                reporter.report(attempts,0,new TimeoutException("Streaming retry budget exhausted"));
                return;
            }
            Exception failure=null;
            try {sender.send(++attempts);} catch(Exception ex){failure=ex;}
            if(future.isDone())return;
            long delay=delays.applyAsLong(attempts);
            reporter.report(attempts,delay,failure);
            remaining=Math.max(0,budgetNanos-(System.nanoTime()-startedNanos));
            scheduled=scheduler.schedule(this::attempt,Math.min(TimeUnit.MILLISECONDS.toNanos(delay),remaining),TimeUnit.NANOSECONDS);
        } catch(Exception ex) {future.completeExceptionally(ex);}
    }
    synchronized boolean complete(T value) {
        if(value==null)throw new IllegalArgumentException("null asset");
        boolean changed=future.complete(value);
        if(scheduled!=null)scheduled.cancel(false);
        return changed;
    }
    synchronized void cancel(){future.cancel(false);if(scheduled!=null)scheduled.cancel(false);}
}
