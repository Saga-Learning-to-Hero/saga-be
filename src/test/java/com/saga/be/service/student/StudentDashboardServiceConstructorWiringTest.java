package com.saga.be.service.student;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.config.DashboardProperties;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.PeerReviewRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamMemberRepository;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class StudentDashboardServiceConstructorWiringTest {

	@Test
	void onlyTheDashboardPropertiesConstructorIsAutowired() {
		Constructor<?>[] constructors = StudentDashboardService.class.getDeclaredConstructors();
		assertThat(constructors).hasSize(2);
		assertThat(Arrays.stream(constructors).filter(ctor -> ctor.getParameterCount() == 0)).isEmpty();
		Constructor<?>[] autowired = Arrays.stream(constructors)
				.filter(ctor -> ctor.isAnnotationPresent(Autowired.class))
				.toArray(Constructor<?>[]::new);
		assertThat(autowired).hasSize(1);
		Class<?>[] params = autowired[0].getParameterTypes();
		assertThat(params[params.length - 1]).isEqualTo(DashboardProperties.class);
	}

	@Test
	void springInstantiatesProductionServiceThroughDashboardPropertiesConstructor() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().setActiveProfiles("local");
			context.register(ProductionWiring.class);
			context.refresh();
			assertThat(context.getBean(StudentDashboardService.class)).isNotNull();
		}
	}

	@Configuration
	@Import(StudentDashboardService.class)
	static class ProductionWiring {

		@Bean
		CourseEnrollmentRepository enrollments() {
			return Mockito.mock(CourseEnrollmentRepository.class);
		}

		@Bean
		TeamMemberRepository members() {
			return Mockito.mock(TeamMemberRepository.class);
		}

		@Bean
		JiraIntegrationRepository jiraIntegrations() {
			return Mockito.mock(JiraIntegrationRepository.class);
		}

		@Bean
		GitRepoRepository repos() {
			return Mockito.mock(GitRepoRepository.class);
		}

		@Bean
		SprintRepository sprints() {
			return Mockito.mock(SprintRepository.class);
		}

		@Bean
		TaskRepository tasks() {
			return Mockito.mock(TaskRepository.class);
		}

		@Bean
		GitCommitRepository commits() {
			return Mockito.mock(GitCommitRepository.class);
		}

		@Bean
		TaskGitCommitLinkRepository commitLinks() {
			return Mockito.mock(TaskGitCommitLinkRepository.class);
		}

		@Bean
		PeerReviewRepository peerReviews() {
			return Mockito.mock(PeerReviewRepository.class);
		}

		@Bean
		IdentityMapRepository identities() {
			return Mockito.mock(IdentityMapRepository.class);
		}

		@Bean
		DashboardProperties dashboardProperties() {
			return new DashboardProperties();
		}
	}
}
