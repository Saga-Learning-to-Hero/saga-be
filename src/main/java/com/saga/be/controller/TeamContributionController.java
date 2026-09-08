package com.saga.be.controller;

import com.saga.be.dto.contribution.ContributionEvaluationResponse;
import com.saga.be.dto.contribution.ContributionOverrideRequest;
import com.saga.be.dto.contribution.ContributionOverrideResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.contribution.TeamContributionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/teams/{teamId}")
@Tag(name = "Team contribution", description = "Live contribution evaluation (DEC-092) mapped onto V2 schema.")
@SecurityRequirement(name = "SAGA_SESSION")
public class TeamContributionController {

	private final TeamContributionService contributions;
	private final UserAccountRepository users;

	public TeamContributionController(TeamContributionService contributions, UserAccountRepository users) {
		this.contributions = contributions;
		this.users = users;
	}

	@GetMapping("/contribution-evaluation")
	@Operation(summary = "Evaluate team contribution percentages")
	public ContributionEvaluationResponse evaluate(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID teamId) {
		return contributions.evaluate(actor(principal), teamId);
	}

	@PostMapping("/contribution-override")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Override a member contribution percentage")
	public ContributionOverrideResponse override(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID teamId,
			@Valid @RequestBody ContributionOverrideRequest request,
			HttpServletRequest http) {
		return contributions.override(actor(principal), teamId, request, audit(http));
	}

	private UserAccount actor(SagaUserPrincipal principal) {
		return users.findById(principal.getUserId()).orElseThrow();
	}

	private static AuditRequest audit(HttpServletRequest http) {
		return new AuditRequest(http.getHeader("X-Request-Id"), http.getRemoteAddr(), http.getHeader("User-Agent"));
	}
}
