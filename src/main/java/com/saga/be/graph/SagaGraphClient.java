package com.saga.be.graph;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
@Profile("!test")
public class SagaGraphClient {

	private final Driver driver;
	private final String database;

	public SagaGraphClient(Driver driver, @Value("${spring.data.neo4j.database:neo4j}") String database) {
		this.driver = driver;
		this.database = database;
	}

	public void write(Consumer<TransactionContext> work) {
		try (Session session = driver.session(SessionConfig.forDatabase(database))) {
			session.executeWrite(tx -> {
				work.accept(tx);
				return true;
			});
		}
	}

	public List<Record> read(String cypher, Map<String, Object> params) {
		try (Session session = driver.session(SessionConfig.forDatabase(database))) {
			return session.executeRead(tx -> tx.run(cypher, params).list());
		}
	}
}
