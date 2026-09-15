package com.saga.be.graph;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class SagaGraphClient {

	private static final Logger log = LoggerFactory.getLogger(SagaGraphClient.class);

	private final Driver driver;
	private final String database;

	public SagaGraphClient(
			Driver driver, @Value("${spring.data.neo4j.database:}") String database) {
		this.driver = driver;
		this.database = database == null ? "" : database.trim();
		if (this.database.isEmpty()) {
			log.info("Neo4j sessions use the server home database (NEO4J_DATABASE unset)");
		} else {
			log.info("Neo4j sessions use database={}", this.database);
		}
	}

	public void write(Consumer<TransactionContext> work) {
		try (Session session = driver.session(sessionConfig(database))) {
			session.executeWrite(tx -> {
				work.accept(tx);
				return true;
			});
		}
	}

	public List<Record> read(String cypher, Map<String, Object> params) {
		try (Session session = driver.session(sessionConfig(database))) {
			return session.executeRead(tx -> tx.run(cypher, params).list());
		}
	}

	public boolean projectExists(UUID projectId) {
		return !read(
						"MATCH (p:Project {id: $id}) RETURN p.id AS id LIMIT 1",
						Map.of("id", SagaGraphIds.project(projectId)))
				.isEmpty();
	}

	static SessionConfig sessionConfig(String database) {
		if (database == null || database.isBlank()) {
			return SessionConfig.defaultConfig();
		}
		return SessionConfig.forDatabase(database.trim());
	}
}
