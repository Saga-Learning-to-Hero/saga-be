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

/** Opt-in entity-versus-schema validation after a disposable MySQL database has migrated through
 * V35. See {@code scripts/verify_v35_mysql.sh}. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "saga.verify.mysql", matches = "true")
@TestPropertySource(properties = {
		"spring.datasource.url=${saga.verify.mysql.url}",
		"spring.datasource.username=${SAGA_VERIFY_MYSQL_USERNAME:${saga.verify.mysql.username:root}}",
		"spring.datasource.password=${SAGA_VERIFY_MYSQL_PASSWORD:${saga.verify.mysql.password:}}",
		"spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=CHAR",
		"spring.flyway.enabled=false",
		"saga.auth.bootstrap-admin.enabled=false"})
class V35MysqlHibernateValidateIT {

	@SpringBootConfiguration
	@EnableAutoConfiguration(excludeName = {
			"org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
			"org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration",
			"org.springframework.boot.session.autoconfigure.SessionAutoConfiguration",
			"org.springframework.boot.session.data.redis.autoconfigure.RedisSessionAutoConfiguration",
			"org.springframework.boot.neo4j.autoconfigure.Neo4jAutoConfiguration",
			"org.springframework.boot.data.neo4j.autoconfigure.DataNeo4jAutoConfiguration",
			"org.springframework.boot.data.neo4j.autoconfigure.DataNeo4jRepositoriesAutoConfiguration",
			"org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
			"org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration",
			"org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
			"org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
			"org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
			"org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration"})
	@EntityScan(basePackages = "com.saga.be.entity")
	@EnableJpaRepositories(basePackages = "com.saga.be.repository")
	static class TxSlice {}

	@Test
	void hibernateSchemaMatchesV35() {
		// Context startup validates canonical_identity_key and retry_attempt against Flyway V35.
	}
}
