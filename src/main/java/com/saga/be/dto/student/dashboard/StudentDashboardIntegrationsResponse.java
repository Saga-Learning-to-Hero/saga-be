package com.saga.be.dto.student.dashboard;

public record StudentDashboardIntegrationsResponse(
		StudentDashboardJiraIntegrationResponse jira, StudentDashboardGithubIntegrationResponse github) {}
