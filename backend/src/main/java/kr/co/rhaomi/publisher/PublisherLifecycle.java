package kr.co.rhaomi.publisher;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;

public final class PublisherLifecycle implements SmartLifecycle {

    private final PublisherControlLoop controlLoop;
    private final PublisherStopSignal stopSignal;
    private final PublisherSettings settings;
    private final boolean autoStart;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread worker;

    PublisherLifecycle(
            PublisherControlLoop controlLoop,
            PublisherStopSignal stopSignal,
            PublisherSettings settings,
            boolean autoStart) {
        this.controlLoop = controlLoop;
        this.stopSignal = stopSignal;
        this.settings = settings;
        this.autoStart = autoStart;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = Thread.ofPlatform().name("rhaomi-publisher-control").unstarted(() -> {
            try {
                controlLoop.run();
            } finally {
                running.set(false);
            }
        });
        worker.start();
    }

    @Override
    public void stop() {
        stopAndJoin();
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stopAndJoin();
        } finally {
            callback.run();
        }
    }

    private void stopAndJoin() {
        stopSignal.requestStop();
        var currentWorker = worker;
        if (currentWorker == null) {
            running.set(false);
            return;
        }
        currentWorker.interrupt();
        try {
            currentWorker.join(settings.shutdownTimeout().toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        running.set(currentWorker.isAlive());
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return autoStart;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
