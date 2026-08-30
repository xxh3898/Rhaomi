package kr.co.rhaomi.publisher;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class AsyncPublicationBuildTaskFactory
        implements PublisherControlLoop.PublicationBuildTaskFactory {

    private final PublicationBuildExecutor buildExecutor;
    private final ExecutorService executorService;

    AsyncPublicationBuildTaskFactory(
            PublicationBuildExecutor buildExecutor, ExecutorService executorService) {
        this.buildExecutor = buildExecutor;
        this.executorService = executorService;
    }

    @Override
    public PublisherControlLoop.PublicationBuildTask start(long targetGeneration) {
        var physicalExecution = new PhysicalExecutionAcknowledgement();
        var future = executorService.submit(() -> {
            if (!physicalExecution.tryStart()) {
                return PublicationBuildResult.TRANSIENT_FAILURE;
            }
            try {
                return Objects.requireNonNull(
                        buildExecutor.execute(targetGeneration), "Publication build result");
            } finally {
                physicalExecution.terminate();
            }
        });
        return new FutureBuildTask(future, physicalExecution);
    }

    private static final class FutureBuildTask
            implements PublisherControlLoop.PublicationBuildTask {

        private final Future<PublicationBuildResult> future;
        private final PhysicalExecutionAcknowledgement physicalExecution;

        private FutureBuildTask(
                Future<PublicationBuildResult> future,
                PhysicalExecutionAcknowledgement physicalExecution) {
            this.future = future;
            this.physicalExecution = physicalExecution;
        }

        @Override
        public PublisherControlLoop.BuildTaskPoll await(Duration timeout)
                throws InterruptedException {
            try {
                return PublisherControlLoop.BuildTaskPoll.completed(
                        future.get(timeout.toNanos(), TimeUnit.NANOSECONDS));
            } catch (TimeoutException exception) {
                return PublisherControlLoop.BuildTaskPoll.pending();
            } catch (ExecutionException exception) {
                throw new PublisherControlLoop.PublicationBuildExecutionException();
            }
        }

        @Override
        public void cancel() {
            physicalExecution.preventFutureStart();
            future.cancel(true);
            physicalExecution.awaitTermination();
        }

        @Override
        public void close() {
            cancel();
        }
    }

    private static final class PhysicalExecutionAcknowledgement {

        private final CountDownLatch terminated = new CountDownLatch(1);
        private PhysicalExecutionState state = PhysicalExecutionState.NOT_STARTED;

        private synchronized boolean tryStart() {
            if (state == PhysicalExecutionState.CANCELLED_BEFORE_START) {
                return false;
            }
            if (state != PhysicalExecutionState.NOT_STARTED) {
                throw new IllegalStateException("Publication build task entered more than once");
            }
            state = PhysicalExecutionState.RUNNING;
            return true;
        }

        private synchronized void preventFutureStart() {
            if (state == PhysicalExecutionState.NOT_STARTED) {
                state = PhysicalExecutionState.CANCELLED_BEFORE_START;
                terminated.countDown();
            }
        }

        private synchronized void terminate() {
            if (state != PhysicalExecutionState.RUNNING) {
                throw new IllegalStateException("Publication build task terminated without running");
            }
            state = PhysicalExecutionState.TERMINATED;
            terminated.countDown();
        }

        private void awaitTermination() {
            var interrupted = false;
            while (true) {
                try {
                    terminated.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private enum PhysicalExecutionState {
        NOT_STARTED,
        RUNNING,
        CANCELLED_BEFORE_START,
        TERMINATED
    }
}
