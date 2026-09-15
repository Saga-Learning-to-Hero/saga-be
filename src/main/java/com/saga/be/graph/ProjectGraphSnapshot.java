package com.saga.be.graph;

import java.util.List;
import java.util.UUID;

public record ProjectGraphSnapshot(
		UUID projectId,
		String projectName,
		TeamNode team,
		List<StudentNode> students,
		List<SprintNode> sprints,
		List<TaskNode> tasks,
		List<CommitNode> commits,
		List<TaskCommitLink> links,
		List<ReviewEdge> reviews) {

	public record TeamNode(UUID id, String name) {}

	public record StudentNode(
			UUID id, String name, String studentCode, String avatar, String role) {}

	public record SprintNode(UUID id, String name, String state) {}

	public record TaskNode(
			UUID id,
			UUID sprintId,
			UUID assigneeStudentId,
			String key,
			String title,
			String status,
			Integer storyPoint,
			String weightType,
			boolean classified,
			boolean anomaly,
			int linkedCommitCount) {}

	public record CommitNode(
			UUID id,
			String sha,
			String message,
			String authorSubject,
			UUID authorStudentId,
			boolean unmapped) {}

	public record TaskCommitLink(UUID taskId, UUID commitId) {}

	public record ReviewEdge(UUID reviewerStudentId, UUID revieweeStudentId, UUID sprintId, int stars) {}
}
