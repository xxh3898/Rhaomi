package kr.co.rhaomi.publisher;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rhaomi.publisher")
public class PublisherProperties {

    private String owner = "";
    private Duration idlePollInterval = Duration.ofSeconds(1);
    private Duration leaseDuration = Duration.ofMinutes(2);
    private Duration leaseRenewalInterval = Duration.ofSeconds(30);
    private Duration shutdownTimeout = Duration.ofSeconds(10);
    private String lockFile = "/var/lib/rhaomi/locks/publisher.lock";
    private boolean autoStart = true;

    public PublisherSettings toSettings() {
        return new PublisherSettings(
                owner,
                idlePollInterval,
                leaseDuration,
                leaseRenewalInterval,
                shutdownTimeout,
                Path.of(lockFile));
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public Duration getIdlePollInterval() {
        return idlePollInterval;
    }

    public void setIdlePollInterval(Duration idlePollInterval) {
        this.idlePollInterval = idlePollInterval;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public Duration getLeaseRenewalInterval() {
        return leaseRenewalInterval;
    }

    public void setLeaseRenewalInterval(Duration leaseRenewalInterval) {
        this.leaseRenewalInterval = leaseRenewalInterval;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }

    public String getLockFile() {
        return lockFile;
    }

    public void setLockFile(String lockFile) {
        this.lockFile = lockFile;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
    }
}
