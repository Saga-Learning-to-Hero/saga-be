package com.saga.be.config;

import java.time.LocalDateTime;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
@EnableJpaRepositories(basePackages = "com.saga.be.repository")
@ConditionalOnClass(name = "jakarta.persistence.EntityManager")
@ConditionalOnBean(DataSource.class)
public class JpaAuditingConfig {

	@Bean
	public DateTimeProvider auditingDateTimeProvider() {
		return () -> Optional.of(LocalDateTime.now());
	}
}
