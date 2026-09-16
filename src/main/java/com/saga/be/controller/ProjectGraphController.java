package com.saga.be.controller;

import com.saga.be.dto.graph.CytoscapeGraphResponse;
import com.saga.be.graph.GraphRead;
import com.saga.be.graph.GraphViewQuery;
import com.saga.be.graph.ProjectGraphService;
import com.saga.be.security.SagaUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
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
	@Operation(summary = "Graph 1 - Student Activity Graph. Optional sprintId and subgraph filters.")
	public ResponseEntity<CytoscapeGraphResponse> overview(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam(required = false) UUID sprintId,
			@RequestParam(required = false) String focusNodeId,
			@RequestParam(required = false) Integer depth,
			@RequestParam(required = false) String nodeTypes,
			@RequestParam(required = false) String edgeTypes,
			@RequestParam(required = false) Boolean anomaliesOnly,
			@RequestParam(required = false) Integer maxNodes,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) String continuationToken,
			@RequestParam(required = false) Boolean includeCommits,
			@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
		return respond(
				ifNoneMatch,
				projectId,
				graphs.overview(
						principal.getUserId(),
						projectId,
						sprintId,
						view(
								focusNodeId,
								depth,
								nodeTypes,
								edgeTypes,
								anomaliesOnly,
								maxNodes,
								cursor,
								continuationToken,
								includeCommits)));
	}

	@GetMapping("/students/{studentId}/graph/contribution")
	@Operation(summary = "Graph 2 - Contribution path for one student. Optional sprintId and subgraph filters.")
	public ResponseEntity<CytoscapeGraphResponse> contribution(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID studentId,
			@RequestParam(required = false) UUID sprintId,
			@RequestParam(required = false) String focusNodeId,
			@RequestParam(required = false) Integer depth,
			@RequestParam(required = false) String nodeTypes,
			@RequestParam(required = false) String edgeTypes,
			@RequestParam(required = false) Boolean anomaliesOnly,
			@RequestParam(required = false) Integer maxNodes,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) String continuationToken,
			@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
		return respond(
				ifNoneMatch,
				projectId,
				graphs.contribution(
						principal.getUserId(),
						projectId,
						studentId,
						sprintId,
						view(focusNodeId, depth, nodeTypes, edgeTypes, anomaliesOnly, maxNodes, cursor, continuationToken)));
	}

	@GetMapping("/sprints/{sprintId}/graph/activity")
	@Operation(summary = "Graph 3 - Sprint activity subgraph.")
	public ResponseEntity<CytoscapeGraphResponse> activity(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID sprintId,
			@RequestParam(required = false) String focusNodeId,
			@RequestParam(required = false) Integer depth,
			@RequestParam(required = false) String nodeTypes,
			@RequestParam(required = false) String edgeTypes,
			@RequestParam(required = false) Boolean anomaliesOnly,
			@RequestParam(required = false) Integer maxNodes,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) String continuationToken,
			@RequestParam(required = false) Boolean includeCommits,
			@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
		return respond(
				ifNoneMatch,
				projectId,
				graphs.activity(
						principal.getUserId(),
						projectId,
						sprintId,
						view(
								focusNodeId,
								depth,
								nodeTypes,
								edgeTypes,
								anomaliesOnly,
								maxNodes,
								cursor,
								continuationToken,
								includeCommits)));
	}

	@GetMapping("/graph/attribution")
	@Operation(summary = "Graph 4 - Commit identity attribution. Optional sprintId and subgraph filters.")
	public ResponseEntity<CytoscapeGraphResponse> attribution(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam(required = false) UUID sprintId,
			@RequestParam(required = false) String focusNodeId,
			@RequestParam(required = false) Integer depth,
			@RequestParam(required = false) String nodeTypes,
			@RequestParam(required = false) String edgeTypes,
			@RequestParam(required = false) Boolean anomaliesOnly,
			@RequestParam(required = false) Integer maxNodes,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) String continuationToken,
			@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
		return respond(
				ifNoneMatch,
				projectId,
				graphs.attribution(
						principal.getUserId(),
						projectId,
						sprintId,
						view(focusNodeId, depth, nodeTypes, edgeTypes, anomaliesOnly, maxNodes, cursor, continuationToken)));
	}

	@GetMapping("/sprints/{sprintId}/graph/peer-review")
	@Operation(summary = "Graph 5 - Peer-review network for one sprint.")
	public ResponseEntity<CytoscapeGraphResponse> peerReview(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID sprintId,
			@RequestParam(required = false) String focusNodeId,
			@RequestParam(required = false) Integer depth,
			@RequestParam(required = false) String nodeTypes,
			@RequestParam(required = false) String edgeTypes,
			@RequestParam(required = false) Boolean anomaliesOnly,
			@RequestParam(required = false) Integer maxNodes,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) String continuationToken,
			@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
		return respond(
				ifNoneMatch,
				projectId,
				graphs.peerReview(
						principal.getUserId(),
						projectId,
						sprintId,
						view(focusNodeId, depth, nodeTypes, edgeTypes, anomaliesOnly, maxNodes, cursor, continuationToken)));
	}

	private static GraphViewQuery view(
			String focusNodeId,
			Integer depth,
			String nodeTypes,
			String edgeTypes,
			Boolean anomaliesOnly,
			Integer maxNodes,
			String cursor,
			String continuationToken) {
		return GraphViewQuery.parse(
				focusNodeId, depth, nodeTypes, edgeTypes, anomaliesOnly, maxNodes, cursor, continuationToken, null);
	}

	private static GraphViewQuery view(
			String focusNodeId,
			Integer depth,
			String nodeTypes,
			String edgeTypes,
			Boolean anomaliesOnly,
			Integer maxNodes,
			String cursor,
			String continuationToken,
			Boolean includeCommits) {
		return GraphViewQuery.parse(
				focusNodeId,
				depth,
				nodeTypes,
				edgeTypes,
				anomaliesOnly,
				maxNodes,
				cursor,
				continuationToken,
				includeCommits);
	}

	private static ResponseEntity<CytoscapeGraphResponse> respond(
			String ifNoneMatch, UUID projectId, GraphRead read) {
		String etag = read.etag(projectId);
		if (etagEquals(ifNoneMatch, etag)) {
			return ResponseEntity.status(304)
					.eTag(etag)
					.header("X-Graph-Revision", Long.toString(read.revision()))
					.build();
		}
		return ResponseEntity.ok()
				.eTag(etag)
				.header("X-Graph-Revision", Long.toString(read.revision()))
				.body(read.body());
	}

	private static boolean etagEquals(String ifNoneMatch, String etag) {
		if (ifNoneMatch == null || ifNoneMatch.isBlank() || "*".equals(ifNoneMatch.trim())) {
			return false;
		}
		String incoming = ifNoneMatch.trim();
		if (incoming.startsWith("W/")) {
			incoming = incoming.substring(2).trim();
		}
		if (incoming.length() >= 2 && incoming.startsWith("\"") && incoming.endsWith("\"")) {
			incoming = incoming.substring(1, incoming.length() - 1);
		}
		return etag.equals(incoming);
	}
}
