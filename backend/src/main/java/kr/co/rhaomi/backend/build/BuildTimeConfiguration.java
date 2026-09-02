package kr.co.rhaomi.backend.build;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BuildTimeConfiguration {

    @Bean
    Clock buildClock() {
        return Clock.systemUTC();
    }
}
