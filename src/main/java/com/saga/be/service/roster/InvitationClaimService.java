package com.saga.be.service.roster;

import com.saga.be.entity.account.StudentCourseInvitation;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.StudentInvitationStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Profile("!test")
public class InvitationClaimService {

	public static final String COURSE_INVITATION_CLAIMED = "COURSE_INVITATION_CLAIMED";

	private static final Logger log = LoggerFactory.getLogger(InvitationClaimService.class);

	private final CourseRosterStore store;

	public InvitationClaimService(CourseRosterStore store) {
		this.store = store;
	}

	public void claimQuietly(UserAccount account) {
		try {
			claimPendingInvitations(account);
		} catch (RuntimeException ex) {
			log.warn("invitation claim skipped category={}", ex.getClass().getSimpleName());
		}
	}

	@Transactional
	public List<StudentCourseInvitation> claimPendingInvitations(UserAccount account) {
		if (account == null || account.getAccountRole() != AccountRole.STUDENT || !StringUtils.hasText(account.getEmail())) {
			return List.of();
		}
		StudentProfile profile = store.findStudentByUserId(account.getId()).orElse(null);
		if (profile == null || !StringUtils.hasText(profile.getStudentCode())) {
			return List.of();
		}
		List<StudentCourseInvitation> claimed = new ArrayList<>();
		for (StudentCourseInvitation invitation : store.listPendingByEmail(account.getEmail())) {
			if (!matches(invitation, account, profile)) {
				continue;
			}
			activateEnrollment(profile, invitation);
			invitation.setStudentProfile(profile);
			invitation.setInvitationStatus(StudentInvitationStatus.CLAIMED);
			invitation.setSentAt(invitation.getSentAt() == null ? LocalDateTime.now() : invitation.getSentAt());
			store.saveInvitation(invitation);
			claimed.add(invitation);
		}
		return claimed;
	}

	private boolean matches(StudentCourseInvitation invitation, UserAccount account, StudentProfile profile) {
		if (invitation.getInvitationStatus() == null || !invitation.getInvitationStatus().isOutstanding()) {
			return false;
		}
		if (!account.getEmail().equalsIgnoreCase(invitation.getEmail())) {
			return false;
		}
		return StringUtils.hasText(invitation.getStudentCode())
				&& invitation.getStudentCode().equalsIgnoreCase(profile.getStudentCode());
	}

	private void activateEnrollment(StudentProfile profile, StudentCourseInvitation invitation) {
		CourseEnrollment enrollment =
				store.findEnrollment(profile.getId(), invitation.getCourse().getId()).orElse(null);
		if (enrollment == null) {
			enrollment = new CourseEnrollment();
			enrollment.setStudentProfile(profile);
			enrollment.setCourse(invitation.getCourse());
			enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
			enrollment.setEnrolledAt(LocalDateTime.now());
			store.saveEnrollment(enrollment);
			return;
		}
		if (enrollment.getEnrollmentStatus() != EnrollmentStatus.ACTIVE) {
			enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
			enrollment.setEnrolledAt(LocalDateTime.now());
			store.saveEnrollment(enrollment);
		}
	}
}
