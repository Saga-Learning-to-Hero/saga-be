package com.saga.be.controller;

import com.saga.be.dto.graph.CytoscapeGraphResponse;
import com.saga.be.graph.ProjectGraphService;
import com.saga.be.security.SagaUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/projects/{projectId}")
@Tag(name = "Project graphs", description = "Cytoscape.js graphs projected into Neo4j for a SAGA project.")
@SecurityRequirement(name = "SAGA_SESSION")
public class ProjectGraphController {

	private final ProjectGraphService graphs;

	public ProjectGraphController(ProjectGraphService graphs) {
		this.graphs = graphs;
	}

	@GetMapping("/graph/overview")
	@Operation(summary = "Graph 1 — Student Activity Graph. Optional sprintId limits tasks/commits to one sprint.")
	public CytoscapeGraphResponse overview(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam(required = false) UUID sprintId) {
		return graphs.overview(principal.getUserId(), projectId, sprintId);
	}

	@GetMapping("/students/{studentId}/graph/contribution")
	@Operation(summary = "Graph 2 — Contribution path for one student. Optional sprintId filter.")
	public CytoscapeGraphResponse contribution(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID studentId,
			@RequestParam(required = false) UUID sprintId) {
		return graphs.contribution(principal.getUserId(), projectId, studentId, sprintId);
	}

	@GetMapping("/sprints/{sprintId}/graph/activity")
	@Operation(summary = "Graph 3 — Sprint activity subgraph.")
	public CytoscapeGraphResponse activity(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID sprintId) {
		return graphs.activity(principal.getUserId(), projectId, sprintId);
	}

	@GetMapping("/graph/attribution")
	@Operation(summary = "Graph 4 — Commit identity attribution. Optional sprintId keeps commits linked to that sprint.")
	public CytoscapeGraphResponse attribution(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam(required = false) UUID sprintId) {
		return graphs.attribution(principal.getUserId(), projectId, sprintId);
	}

	@GetMapping("/sprints/{sprintId}/graph/peer-review")
	@Operation(summary = "Graph 5 — Peer-review network for one sprint.")
	public CytoscapeGraphResponse peerReview(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID sprintId) {
		return graphs.peerReview(principal.getUserId(), projectId, sprintId);
	}
}
