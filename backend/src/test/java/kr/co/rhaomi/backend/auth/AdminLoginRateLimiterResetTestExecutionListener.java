package kr.co.rhaomi.backend.auth;

import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

public final class AdminLoginRateLimiterResetTestExecutionListener
        extends AbstractTestExecutionListener {

    @Override
    public void beforeTestMethod(TestContext testContext) {
        var limiter = testContext
                .getApplicationContext()
                .getBeanProvider(AdminLoginRateLimiter.class)
                .getIfAvailable();
        if (limiter != null) {
            limiter.resetForTesting();
        }
    }
}
