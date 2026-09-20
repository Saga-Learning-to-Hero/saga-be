package com.saga.be.repository;

import com.saga.be.entity.jira.JiraTaskFailoverRemoteIssueBinding;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JiraTaskFailoverRemoteIssueBindingRepository
		extends JpaRepository<JiraTaskFailoverRemoteIssueBinding, UUID> {

	Optional<JiraTaskFailoverRemoteIssueBinding>
			findByTargetJiraIntegration_IdAndRemoteIssueId(UUID targetIntegrationId, String remoteIssueId);
}
