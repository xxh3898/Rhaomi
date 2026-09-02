package kr.co.rhaomi.publisher;

import java.util.Optional;

public interface PublicationExecutionLock {

    Optional<Handle> tryAcquire();

    interface Handle extends AutoCloseable {

        @Override
        void close();
    }
}
