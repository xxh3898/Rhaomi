package kr.co.rhaomi.publisher;

import java.time.Duration;
import java.util.Objects;
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
        var future = executorService.submit(() -> Objects.requireNonNull(
                buildExecutor.execute(targetGeneration), "Publication build result"));
        return new FutureBuildTask(future);
    }

    private static final class FutureBuildTask
            implements PublisherControlLoop.PublicationBuildTask {

        private final Future<PublicationBuildResult> future;

        private FutureBuildTask(Future<PublicationBuildResult> future) {
            this.future = future;
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
            future.cancel(true);
        }

        @Override
        public void close() {
            if (!future.isDone()) {
                cancel();
            }
        }
    }
}
