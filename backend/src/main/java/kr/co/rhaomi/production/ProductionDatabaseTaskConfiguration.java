package kr.co.rhaomi.production;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EntityScan("kr.co.rhaomi.backend")
class ProductionDatabaseTaskConfiguration {}
