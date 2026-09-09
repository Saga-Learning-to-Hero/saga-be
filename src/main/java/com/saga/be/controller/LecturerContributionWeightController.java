package com.saga.be.controller;

import com.saga.be.dto.contribution.ContributionConfigModeRequest;
import com.saga.be.dto.contribution.ContributionSliceWeightsRequest;
import com.saga.be.dto.contribution.ContributionTeamWeightsResponse;
import com.saga.be.dto.contribution.CourseContributionWeightsResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.contribution.CourseContributionWeightService;
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
@RequestMapping("/api/lecturer/courses/{courseId}")
@Tag(
		name = "Course contribution weights",
		description = "Course-default or project-group contribution weights. Assigned lecturer or ADMIN.")
@SecurityRequirement(name = "SAGA_SESSION")
public class LecturerContributionWeightController {

	private final CourseContributionWeightService weights;
	private final UserAccountRepository users;

	public LecturerContributionWeightController(
			CourseContributionWeightService weights, UserAccountRepository users) {
		this.weights = weights;
		this.users = users;
	}

	@GetMapping("/contribution-slice-weights")
	@Operation(summary = "Get course contribution slice weights")
	public CourseContributionWeightsResponse getWeights(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId) {
		return weights.getWeights(actor(principal), courseId);
	}

	@PutMapping("/contribution-slice-weights")
	@Operation(summary = "Update course contribution slice weights (COURSE mode)")
	public CourseContributionWeightsResponse putWeights(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@Valid @RequestBody ContributionSliceWeightsRequest request,
			HttpServletRequest http) {
		return weights.updateWeights(actor(principal), courseId, request, audit(http));
	}

	@PutMapping("/contribution-config-mode")
	@Operation(summary = "Switch COURSE vs PROJECT_GROUP contribution weight mode")
	public CourseContributionWeightsResponse putMode(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@Valid @RequestBody ContributionConfigModeRequest request,
			HttpServletRequest http) {
		return weights.switchMode(actor(principal), courseId, request.mode(), audit(http));
	}

	@GetMapping("/contribution-team-weights")
	@Operation(summary = "List whether each team has project-group weights")
	public ContributionTeamWeightsResponse teamWeights(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId) {
		return weights.listTeamWeights(actor(principal), courseId);
	}

	private UserAccount actor(SagaUserPrincipal principal) {
		return users.findById(principal.getUserId()).orElseThrow();
	}

	private static AuditRequest audit(HttpServletRequest http) {
		return new AuditRequest(http.getHeader("X-Request-Id"), http.getRemoteAddr(), http.getHeader("User-Agent"));
	}
}
