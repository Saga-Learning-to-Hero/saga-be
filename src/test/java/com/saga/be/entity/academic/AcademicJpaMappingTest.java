package com.saga.be.entity.academic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.saga.be.entity.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

class AcademicJpaMappingTest {

	@Test
	void entityManagerFactoryMetadataAcceptsV5CompositeAssociations() throws Exception {
		StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
				.applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
				.applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
				.build();
		try {
			MetadataSources sources = new MetadataSources(registry);
			ClassPathScanningCandidateComponentProvider scanner =
					new ClassPathScanningCandidateComponentProvider(false);
			scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
			scanner.addIncludeFilter(new AnnotationTypeFilter(MappedSuperclass.class));
			for (BeanDefinition candidate : scanner.findCandidateComponents("com.saga.be.entity")) {
				sources.addAnnotatedClass(Class.forName(candidate.getBeanClassName()));
			}
			sources.addAnnotatedClass(BaseEntity.class);
			Metadata metadata = sources.buildMetadata();
			assertFalse(metadata.getEntityBindings().isEmpty());
			assertNotNull(metadata.getEntityBinding(SyllabusDeliverableLearningOutcome.class.getName()));
			assertNotNull(metadata.getEntityBinding(SyllabusPhaseLearningOutcome.class.getName()));
			assertNotNull(metadata.getEntityBinding(SyllabusLearningUnitOutcome.class.getName()));
			assertNotNull(metadata.getEntityBinding(SyllabusExpectedActivity.class.getName()));
			assertNotNull(metadata.getEntityBinding(SyllabusExpectedDeliverable.class.getName()));
			try (SessionFactory sessionFactory = metadata.buildSessionFactory()) {
				assertNotNull(sessionFactory);
			}
		} finally {
			StandardServiceRegistryBuilder.destroy(registry);
		}
	}
}
