package kr.co.rhaomi.production;

@FunctionalInterface
interface InitialContentImportCheckpoint {

    void afterWritesBeforeCommit();

    static InitialContentImportCheckpoint noop() {
        return () -> {};
    }
}
