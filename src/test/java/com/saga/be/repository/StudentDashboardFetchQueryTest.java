package com.saga.be.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StudentDashboardFetchQueryTest {

	@Test
	void phaseAQueriesAreFetchedAndAggregatedWithoutPerRowLoops() throws Exception {
		String enrollments = Files.readString(Path.of("src/main/java/com/saga/be/repository/CourseEnrollmentRepository.java"));
		assertTrue(enrollments.contains("findFetchedActiveByUserAndCourse"));
		assertTrue(enrollments.contains("JOIN FETCH e.studentProfile"));
		assertTrue(enrollments.contains("JOIN FETCH p.userAccount"));
		assertTrue(enrollments.contains("JOIN FETCH e.course"));
		assertTrue(enrollments.contains("JOIN FETCH c.subject"));
		assertTrue(enrollments.contains("JOIN FETCH c.semester"));
		assertTrue(enrollments.contains("c.deletedAt IS NULL"));

		String members = Files.readString(Path.of("src/main/java/com/saga/be/repository/TeamMemberRepository.java"));
		assertTrue(members.contains("findFetchedByCourseEnrollment_Id"));
		assertTrue(members.contains("countActiveByTeam_Id"));
		assertTrue(members.contains("e.enrollmentStatus = com.saga.be.entity.enums.EnrollmentStatus.ACTIVE"));

		String repos = Files.readString(Path.of("src/main/java/com/saga/be/repository/GitRepoRepository.java"));
		assertTrue(repos.contains("countAndMaxLastSyncedAtGroupedByStatus"));
		assertTrue(repos.contains("group by r.connectionStatus"));

		String tasks = Files.readString(Path.of("src/main/java/com/saga/be/repository/TaskRepository.java"));
		assertTrue(tasks.contains("countGroupedByStatusForProjectAndSprint"));
		assertTrue(tasks.contains("countStatusAndStoryPointsForAssignee"));
		assertTrue(tasks.contains("findAttentionNonDoneByProjectAndAssignee"));
		assertTrue(tasks.contains("findDoneWithoutV23EvidenceCandidates"));
		assertTrue(tasks.contains("StudentDashboardAnomalyCandidateRow"));
		assertTrue(tasks.contains("coalesce(t.storyPoint, 0)"));
		int nonDone = tasks.indexOf("findAttentionNonDoneByProjectAndAssignee");
		assertTrue(nonDone > 0);
		String nonDoneQuery = tasks.substring(nonDone - 700, nonDone);
		assertTrue(nonDoneQuery.contains("case when t.dueDate is null then 1 else 0 end"));
		assertTrue(nonDoneQuery.contains("t.dueDate asc"));
		assertTrue(nonDoneQuery.contains("Priority.HIGHEST then 5"));
		assertTrue(nonDoneQuery.contains("t.id asc"));
		assertFalse(tasks.contains("MSR_CANDIDATE_FETCH_LIMIT"));
		assertFalse(tasks.contains("findDoneCodingWithoutV23Evidence"));
		int personalMetrics = tasks.indexOf("countStatusAndStoryPointsForAssignee");
		assertTrue(personalMetrics > 0);
		assertFalse(tasks.substring(personalMetrics - 500, personalMetrics).contains("t.sprint"));

		String commits = Files.readString(Path.of("src/main/java/com/saga/be/repository/GitCommitRepository.java"));
		assertTrue(commits.contains("countAndMaxCommittedAtByProjectAndAuthor"));
		assertTrue(commits.contains("findRecentAuthoredV23ByProject"));
		assertTrue(commits.contains("findWeeklyCommittedAtByProjectAndAuthor"));
		assertTrue(commits.contains("c.committedAt is not null"));
		assertTrue(commits.contains("c.parentCount is null or c.parentCount <= 1"));
		assertTrue(commits.contains("c.authorStudent.id = :studentId"));
		int weekly = commits.indexOf("findWeeklyCommittedAtByProjectAndAuthor");
		assertTrue(weekly > 0);
		assertFalse(commits.substring(weekly - 700, weekly).contains("coalesce(c.committedAt"));

		String links = Files.readString(Path.of("src/main/java/com/saga/be/repository/TaskGitCommitLinkRepository.java"));
		assertTrue(links.contains("countDistinctLinkedAuthoredV23"));
		assertTrue(links.contains("count(distinct c.id)"));
		assertTrue(links.contains("countRawAndV23LinksByTaskIds"));
		assertTrue(links.contains("findExternalKeysByCommitIds"));

		String reviews = Files.readString(Path.of("src/main/java/com/saga/be/repository/PeerReviewRepository.java"));
		assertTrue(reviews.contains("countRemainingPeers"));
		assertTrue(reviews.contains("NOT EXISTS"));
		assertTrue(reviews.contains("pr.reviewerStudent.id = :reviewerStudentId"));
		assertTrue(reviews.contains("pr.revieweeStudent.id = sp.id"));
		assertTrue(reviews.contains("e.enrollmentStatus = com.saga.be.entity.enums.EnrollmentStatus.ACTIVE"));

		String service = Files.readString(Path.of("src/main/java/com/saga/be/service/student/StudentDashboardService.java"));
		assertTrue(service.contains("@Transactional(readOnly = true)"));
		assertTrue(service.contains("INTERACTIVE_NORMAL") || Files.readString(Path.of("src/main/java/com/saga/be/controller/StudentDashboardController.java")).contains("INTERACTIVE_NORMAL"));
		assertFalse(service.contains("requireStudentLeader("));
		assertFalse(service.contains("requireLecturerOrTeamLeader("));
		assertFalse(service.contains("requireReader("));
		assertFalse(service.contains("ensureFresh("));
		assertFalse(service.contains("RedisTemplate"));
		assertFalse(service.contains("TeamContributionService"));
		assertFalse(service.contains("evaluateTeam"));
		assertTrue(service.contains("weeklyCommits"));
		assertTrue(service.contains("findWeeklyCommittedAtByProjectAndAuthor"));
		assertTrue(service.contains("LocalDate.now(clock)"));
		assertFalse(service.contains("LocalDateTime.now("));
		assertFalse(service.contains("Neo4j"));
		assertFalse(service.contains("Firebase"));
		assertFalse(service.contains("GitHubClient"));
		assertFalse(service.contains("JiraClient"));
		assertFalse(service.contains("MSR_CANDIDATE_FETCH_LIMIT"));
		assertFalse(service.contains("commit.getCommittedAt() != null ? commit.getCommittedAt() : commit.getCreatedAt()"));
		assertTrue(service.contains("commit.getCommittedAt()"));
		assertTrue(service.contains("classifyAnomalies"));
		assertTrue(service.contains("actionableAlerts"));
		assertTrue(service.contains("countRemainingPeers"));
		assertTrue(service.contains("MSR_ANOMALY"));
		assertTrue(service.contains("PEER_REVIEW_PENDING"));
		assertFalse(service.contains("GHOSTING_WARNING"));
		assertFalse(service.contains("ProjectGraphService"));
		assertFalse(service.contains("PeerReviewService"));
		assertFalse(service.contains("findBySprint_IdAndReviewerStudent_IdAndRevieweeStudent_Id"));
	}
}
