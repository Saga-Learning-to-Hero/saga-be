package com.saga.be.controller;

import com.saga.be.dto.contribution.ProjectGroupWeightsRequest;
import com.saga.be.dto.contribution.ProjectGroupWeightsResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.contribution.ProjectGroupWeightConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/projects/{projectId}/group-weights")
@Tag(name = "Project group weights", description = "Per-project contribution weights when course mode is PROJECT_GROUP.")
@SecurityRequirement(name = "SAGA_SESSION")
public class ProjectGroupWeightController {

	private final ProjectGroupWeightConfigService weights;
	private final UserAccountRepository users;

	public ProjectGroupWeightController(ProjectGroupWeightConfigService weights, UserAccountRepository users) {
		this.weights = weights;
		this.users = users;
	}

	@GetMapping
	@Operation(summary = "Get project-group contribution weights")
	public ProjectGroupWeightsResponse get(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return weights.get(actor(principal), projectId);
	}

	@PutMapping
	@Operation(summary = "Create or replace project-group contribution weights")
	public ProjectGroupWeightsResponse put(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@Valid @RequestBody ProjectGroupWeightsRequest request,
			HttpServletRequest http) {
		return weights.update(actor(principal), projectId, request, audit(http));
	}

	private UserAccount actor(SagaUserPrincipal principal) {
		return users.findById(principal.getUserId()).orElseThrow();
	}

	private static AuditRequest audit(HttpServletRequest http) {
		return new AuditRequest(http.getHeader("X-Request-Id"), http.getRemoteAddr(), http.getHeader("User-Agent"));
	}
}
