package com.saga.be.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

/**
 * Opt-in Hibernate {@code ddl-auto=validate} against a disposable MySQL migrated through V31.
 * Enabled only when {@code saga.verify.mysql=true}.
 *
 * <p>This is the exact class of check that V30 skipped: H2's {@code ddl-auto=create-drop}
 * (used by every other test in this suite) generates its schema FROM the entities, so a migration
 * that forgets a column an entity expects is invisible to it. V30 added {@code ai_analysis_adjudication}
 * without {@code updated_at} even though the entity extends {@link com.saga.be.entity.BaseEntity}
 * (which always requires it), and no test caught it before it reached production. V31 adds the
 * missing column; this test locks that fix in against a real Hibernate+MySQL validation pass.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "saga.verify.mysql", matches = "true")
@TestPropertySource(
		properties = {
			"spring.datasource.url=${saga.verify.mysql.url}",
			"spring.datasource.username=root",
			"spring.datasource.password=",
			"spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
			"spring.jpa.hibernate.ddl-auto=validate",
			"spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=CHAR",
			"spring.flyway.enabled=false",
			"saga.auth.bootstrap-admin.enabled=false"
		})
class V31MysqlHibernateValidateIT {

	@SpringBootConfiguration
	@EnableAutoConfiguration(
			excludeName = {
				"org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
				"org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration",
				"org.springframework.boot.session.autoconfigure.SessionAutoConfiguration",
				"org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
				"org.springframework.boot.neo4j.autoconfigure.Neo4jAutoConfiguration",
				"org.springframework.boot.data.neo4j.autoconfigure.DataNeo4jAutoConfiguration",
				"org.springframework.boot.data.neo4j.autoconfigure.DataNeo4jRepositoriesAutoConfiguration",
				"org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
				"org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration",
				"org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration"
			})
	@EntityScan(basePackages = "com.saga.be.entity")
	@EnableJpaRepositories(basePackages = "com.saga.be.repository")
	static class TxSlice {}

	@Test
	void hibernateSchemaMatchesV31() {
		// Context load with ddl-auto=validate is the assertion: it fails startup if any
		// BaseEntity-derived table is missing created_at/updated_at, or any other entity/column
		// mismatch exists across the full schema through V31.
	}
}
