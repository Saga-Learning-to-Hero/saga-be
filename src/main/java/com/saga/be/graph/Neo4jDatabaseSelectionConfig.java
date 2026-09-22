package com.saga.be.graph;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.neo4j.core.DatabaseSelectionProvider;
import org.springframework.data.neo4j.core.ReactiveDatabaseSelectionProvider;

/**
 * Replaces Spring Boot's imperative and reactive database selection beans. An empty
 * {@code spring.data.neo4j.database} (Aura: {@code NEO4J_DATABASE} left blank)
 * must use the server home database. Boot passes that empty string through and
 * rejects it. Reactor on the classpath turns the reactive bean on even though
 * graph access is imperative.
 */
@Configuration
@Profile("!test")
public class Neo4jDatabaseSelectionConfig {

	@Bean
	public DatabaseSelectionProvider databaseSelectionProvider(
			@Value("${spring.data.neo4j.database:}") String database) {
		return selectionProvider(database);
	}

	@Bean
	public ReactiveDatabaseSelectionProvider reactiveDatabaseSelectionProvider(
			@Value("${spring.data.neo4j.database:}") String database) {
		return reactiveSelectionProvider(database);
	}

	static DatabaseSelectionProvider selectionProvider(String database) {
		if (database == null || database.isBlank()) {
			return DatabaseSelectionProvider.getDefaultSelectionProvider();
		}
		return DatabaseSelectionProvider.createStaticDatabaseSelectionProvider(database.trim());
	}

	static ReactiveDatabaseSelectionProvider reactiveSelectionProvider(String database) {
		if (database == null || database.isBlank()) {
			return ReactiveDatabaseSelectionProvider.getDefaultSelectionProvider();
		}
		return ReactiveDatabaseSelectionProvider.createStaticDatabaseSelectionProvider(database.trim());
	}
}
