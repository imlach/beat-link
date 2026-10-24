package org.deepsymmetry.beatlink.data;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class StreamingRequestTest {
    @Test public void delaysAreCapped() {
        assertEquals(1500,StreamingRequest.delay(1));
        assertEquals(1500,StreamingRequest.delay(3));
        assertEquals(3000,StreamingRequest.delay(4));
        assertEquals(6000,StreamingRequest.delay(5));
        assertEquals(30000,StreamingRequest.delay(100));
    }
    @Test public void recoversAfterInitialThreeFailuresAndCachesSuccess() throws Exception {
        ScheduledExecutorService executor=Executors.newScheduledThreadPool(2);
        try {
            AtomicInteger sends=new AtomicInteger();CountDownLatch fourth=new CountDownLatch(1);
            AtomicInteger failures=new AtomicInteger();
            StreamingRequest<String> r=new StreamingRequest<>(executor,()->true,n->{
                sends.incrementAndGet();if(n<=3)throw new java.io.IOException("simulated loss");fourth.countDown();
            },(n,d,e)->{if(e!=null)failures.incrementAndGet();},n->10);
            r.start();r.start();assertTrue(fourth.await(2,TimeUnit.SECONDS));
            assertTrue(r.complete("waveform"));assertEquals("waveform",r.future.get());assertEquals(3,failures.get());
            int count=sends.get();Thread.sleep(50);r.start();assertEquals(count,sends.get());
        } finally {executor.shutdownNow();}
    }
    @Test public void callerTimeoutDoesNotPoisonRequest() throws Exception {
        ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor();
        try {
            StreamingRequest<String> r=new StreamingRequest<>(executor,()->true,n->{},(n,d,e)->{},n->10);
            r.start();try{r.future.get(1,TimeUnit.MILLISECONDS);fail("expected timeout");}catch(TimeoutException expected){}
            assertFalse(r.future.isDone());r.complete("late");assertEquals("late",r.future.get());
        }finally{executor.shutdownNow();}
    }
    @Test public void unloadCancelsAndRejectsLateResponse() throws Exception {
        ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicBoolean loaded=new AtomicBoolean(true);CountDownLatch sent=new CountDownLatch(1);
            StreamingRequest<String> r=new StreamingRequest<>(executor,loaded::get,n->sent.countDown(),(n,d,e)->{},n->10);
            r.start();assertTrue(sent.await(2,TimeUnit.SECONDS));loaded.set(false);
            try{r.future.get(2,TimeUnit.SECONDS);fail("expected cancellation");}catch(CancellationException expected){}
            assertFalse(r.complete("stale"));
        }finally{executor.shutdownNow();}
    }
    @Test public void stoppedRequestNeverSends() throws Exception {
        ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicInteger sends=new AtomicInteger();
            StreamingRequest<String> r=new StreamingRequest<>(executor,()->true,n->sends.incrementAndGet(),(n,d,e)->{},n->10);
            r.cancel();r.start();Thread.sleep(30);assertEquals(0,sends.get());assertTrue(r.future.isCancelled());
        }finally{executor.shutdownNow();}
    }
}
