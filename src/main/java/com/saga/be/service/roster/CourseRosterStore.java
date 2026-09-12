package com.saga.be.service.roster;

import com.saga.be.entity.account.StudentCourseInvitation;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.StudentInvitationStatus;
import com.saga.be.entity.project.TeamMember;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface CourseRosterStore {

	Optional<Course> findCourse(UUID courseId);

	Optional<UserAccount> findUserByEmail(String email);

	List<UserAccount> findUsersByEmails(Collection<String> emails);

	Optional<StudentProfile> findStudentByUserId(UUID userId);

	List<StudentProfile> findStudentsByUserIds(Collection<UUID> userIds);

	Optional<StudentProfile> findStudentByCode(String studentCode);

	List<StudentProfile> findStudentsByCodes(Collection<String> studentCodes);

	Optional<CourseEnrollment> findEnrollment(UUID studentProfileId, UUID courseId);

	List<CourseEnrollment> listEnrollments(UUID courseId);

	CourseEnrollment saveEnrollment(CourseEnrollment enrollment);

	Optional<CourseEnrollment> findEnrollmentById(UUID enrollmentId);

	Optional<StudentCourseInvitation> findInvitationByCourseAndEmail(UUID courseId, String email);

	Optional<StudentCourseInvitation> findInvitationByCourseAndStudentCode(UUID courseId, String studentCode);

	Optional<StudentCourseInvitation> findInvitationById(UUID invitationId);

	List<StudentCourseInvitation> listInvitations(UUID courseId);

	List<StudentCourseInvitation> listPendingByEmail(String email);

	StudentCourseInvitation saveInvitation(StudentCourseInvitation invitation);

	StudentProfile saveStudent(StudentProfile profile);

	/**
	 * The id of the team this enrollment currently belongs to, if any — a scalar projection that
	 * does NOT hydrate a managed {@link TeamMember} entity. Callers that need to decide whether a
	 * removal is safe MUST get the team id this way and then call
	 * {@link #lockTeamAndReloadMembership} for the actual membership row — never load a
	 * {@link TeamMember} unlocked first and re-query it "under lock" afterward in the same
	 * transaction: Hibernate's persistence-context identity map returns the SAME already-loaded
	 * (and by then stale) Java object for a repeat query by id, silently ignoring the fact that a
	 * concurrent transaction changed the row in between. Keeping the first lookup as a scalar
	 * projection guarantees the entity is hydrated for the first time only after the lock is held.
	 */
	Optional<UUID> findTeamIdByEnrollment(UUID enrollmentId);

	/**
	 * Acquires the same pessimistic write lock on the team that
	 * {@code LecturerTeamService#replaceLeader}/{@code moveMember} take before mutating any
	 * {@link TeamMember#getRoleInTeam()} on that team, then loads this enrollment's membership row
	 * while holding that lock — the first (and only) time that row is hydrated in this
	 * transaction, so it reflects whatever a concurrent leader reassignment already committed.
	 */
	Optional<TeamMember> lockTeamAndReloadMembership(UUID teamId, UUID enrollmentId);

	/**
	 * Permanently removes a {@link TeamMember} row as part of roster removal. Safe with zero
	 * migration: nothing in the schema holds a foreign key to {@code team_member.id} — contribution
	 * evidence (tasks, commits, work sessions, contribution confirmations, pull requests/reviews)
	 * all key off {@code user_account}/{@code student_profile}/{@code project}/{@code task}
	 * directly, never {@code team_member}. This is also the only zero-migration way to stop a later
	 * re-add from silently resurrecting old team/leader membership: {@code TeamMember}'s
	 * "active"-ness is derived purely from its linked {@code CourseEnrollment.enrollmentStatus}, and
	 * that same enrollment row is reused/reactivated on re-add (see
	 * {@link CourseRosterService#enrollExisting}), so a preserved row would silently become "active"
	 * again the moment the enrollment turns back ACTIVE.
	 */
	void deleteTeamMembership(TeamMember member);

	default <T> T inTransaction(Supplier<T> action) {
		return action.get();
	}
}
