package kr.co.rhaomi.publisher;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rhaomi.publisher.executor")
public class PublicationExecutorProperties {

    private String nodeExecutable = "";
    private String releaseScript = "";
    private Duration terminationGrace = Duration.ofSeconds(2);
    private String buildApiInternalUrl = "";
    private String buildApiCredential = "";
    private String sourceRoot = "";
    private String workRoot = "";
    private String releaseRoot = "";
    private String currentLink = "";
    private String previousLink = "";
    private String publicSiteUrl = "";
    private String codeSha = "";
    private String codeImageTag = "";
    private String codeImageDigest = "";
    private String flywayVersion = "";
    private String sbomReference = "";
    private String buildTimeoutMs = "";
    private String releaseRetention = "5";

    PublicationExecutorSettings toSettings() {
        var environment = new LinkedHashMap<String, String>();
        environment.put("BUILD_API_INTERNAL_URL", buildApiInternalUrl);
        environment.put("BUILD_API_CREDENTIAL", buildApiCredential);
        environment.put("RHAOMI_PUBLISHER_SOURCE_ROOT", sourceRoot);
        environment.put("RHAOMI_PUBLISHER_WORK_ROOT", workRoot);
        environment.put("RHAOMI_PUBLIC_RELEASE_ROOT", releaseRoot);
        environment.put("RHAOMI_PUBLIC_CURRENT_LINK", currentLink);
        environment.put("RHAOMI_PUBLIC_PREVIOUS_LINK", previousLink);
        environment.put("PUBLIC_SITE_URL", publicSiteUrl);
        environment.put("RHAOMI_CODE_SHA", codeSha);
        environment.put("RHAOMI_CODE_IMAGE_TAG", codeImageTag);
        environment.put("RHAOMI_CODE_IMAGE_DIGEST", codeImageDigest);
        environment.put("RHAOMI_FLYWAY_VERSION", flywayVersion);
        environment.put("RHAOMI_SBOM_REFERENCE", sbomReference);
        if (!buildTimeoutMs.isBlank()) {
            environment.put("RHAOMI_PUBLISHER_BUILD_TIMEOUT_MS", buildTimeoutMs);
        }
        environment.put("RHAOMI_RELEASE_RETENTION", releaseRetention);
        var path = System.getenv("PATH");
        if (path != null) environment.put("PATH", path);
        return new PublicationExecutorSettings(
                Path.of(nodeExecutable),
                Path.of(releaseScript),
                terminationGrace,
                environment);
    }

    public String getNodeExecutable() {
        return nodeExecutable;
    }

    public void setNodeExecutable(String value) {
        nodeExecutable = value;
    }

    public String getReleaseScript() {
        return releaseScript;
    }

    public void setReleaseScript(String value) {
        releaseScript = value;
    }

    public Duration getTerminationGrace() {
        return terminationGrace;
    }

    public void setTerminationGrace(Duration value) {
        terminationGrace = value;
    }

    public String getBuildApiInternalUrl() {
        return buildApiInternalUrl;
    }

    public void setBuildApiInternalUrl(String value) {
        buildApiInternalUrl = value;
    }

    public String getBuildApiCredential() {
        return buildApiCredential;
    }

    public void setBuildApiCredential(String value) {
        buildApiCredential = value;
    }

    public String getSourceRoot() {
        return sourceRoot;
    }

    public void setSourceRoot(String value) {
        sourceRoot = value;
    }

    public String getWorkRoot() {
        return workRoot;
    }

    public void setWorkRoot(String value) {
        workRoot = value;
    }

    public String getReleaseRoot() {
        return releaseRoot;
    }

    public void setReleaseRoot(String value) {
        releaseRoot = value;
    }

    public String getCurrentLink() {
        return currentLink;
    }

    public void setCurrentLink(String value) {
        currentLink = value;
    }

    public String getPreviousLink() {
        return previousLink;
    }

    public void setPreviousLink(String value) {
        previousLink = value;
    }

    public String getPublicSiteUrl() {
        return publicSiteUrl;
    }

    public void setPublicSiteUrl(String value) {
        publicSiteUrl = value;
    }

    public String getCodeSha() {
        return codeSha;
    }

    public void setCodeSha(String value) {
        codeSha = value;
    }

    public String getCodeImageTag() {
        return codeImageTag;
    }

    public void setCodeImageTag(String value) {
        codeImageTag = value;
    }

    public String getCodeImageDigest() {
        return codeImageDigest;
    }

    public void setCodeImageDigest(String value) {
        codeImageDigest = value;
    }

    public String getFlywayVersion() {
        return flywayVersion;
    }

    public void setFlywayVersion(String value) {
        flywayVersion = value;
    }

    public String getSbomReference() {
        return sbomReference;
    }

    public void setSbomReference(String value) {
        sbomReference = value;
    }

    public String getBuildTimeoutMs() {
        return buildTimeoutMs;
    }

    public void setBuildTimeoutMs(String value) {
        buildTimeoutMs = value;
    }

    public String getReleaseRetention() {
        return releaseRetention;
    }

    public void setReleaseRetention(String value) {
        releaseRetention = value;
    }
}
