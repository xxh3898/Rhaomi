package kr.co.rhaomi.publisher;

@FunctionalInterface
public interface PublicationBuildExecutor {

    PublicationBuildResult execute(long targetGeneration);
}
