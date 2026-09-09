package com.saga.be.service.contribution;

import com.saga.be.entity.academic.Course;
import com.saga.be.entity.assessment.ProjectGroupWeightConfig;
import com.saga.be.entity.enums.ContributionConfigMode;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.ProjectGroupWeightConfigRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class ContributionSliceWeightResolver {

	private final ProjectGroupWeightConfigRepository configs;

	public ContributionSliceWeightResolver(ProjectGroupWeightConfigRepository configs) {
		this.configs = configs;
	}

	public ContributionSliceWeights resolve(Team team) {
		Course course = team == null ? null : team.getCourse();
		if (course == null) {
			return ContributionSliceWeights.equalQuarters();
		}
		if (course.getContributionConfigMode() != ContributionConfigMode.PROJECT_GROUP) {
			return ContributionSliceWeights.fromCourse(course);
		}
		Project project = team.getProject();
		if (project == null) {
			throw incomplete();
		}
		ProjectGroupWeightConfig config = configs.findByProject_Id(project.getId()).orElse(null);
		if (config == null || config.getTeam() == null || !config.getTeam().getId().equals(team.getId())) {
			throw incomplete();
		}
		return ContributionSliceWeights.fromProjectConfig(config);
	}

	private static AcademicException incomplete() {
		return new AcademicException(
				AcademicErrorCode.TEAM_WEIGHT_CONFIG_INCOMPLETE,
				HttpStatus.CONFLICT,
				"Project-group contribution weights are missing for this team.");
	}
}
