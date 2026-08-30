package kr.co.rhaomi.publisher;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PublisherStopSignal {

    private final AtomicBoolean requested = new AtomicBoolean();
    private final CountDownLatch requestedLatch = new CountDownLatch(1);

    public boolean isRequested() {
        return requested.get();
    }

    public void requestStop() {
        if (requested.compareAndSet(false, true)) {
            requestedLatch.countDown();
        }
    }

    boolean await(Duration duration) {
        if (isRequested()) {
            return false;
        }
        try {
            return !requestedLatch.await(duration.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            requestStop();
            return false;
        }
    }
}
