package com.saga.be.service.academic;

import com.saga.be.dto.academic.LecturerDirectoryResponse;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.web.RequestTiming;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Profile("!test")
public class AdminLecturerService {

	private static final int SEARCH_MAX_LENGTH = 255;

	private final LecturerProfileRepository lecturers;

	public AdminLecturerService(LecturerProfileRepository lecturers) {
		this.lecturers = lecturers;
	}

	@Transactional(readOnly = true)
	public List<LecturerDirectoryResponse> list(Boolean active, String search) {
		return RequestTiming.record("listLecturers", () -> {
			boolean assignable = active == null || active;
			return lecturers
					.searchDirectory(assignable, AccountRole.LECTURER, AccountStatus.ACTIVE, normalizeSearch(search))
					.stream()
					.map(AdminLecturerService::toResponse)
					.toList();
		});
	}

	static boolean isAssignable(UserAccount user) {
		return user != null
				&& user.getAccountRole() == AccountRole.LECTURER
				&& user.getAccountStatus() == AccountStatus.ACTIVE;
	}

	private static LecturerDirectoryResponse toResponse(LecturerProfile profile) {
		UserAccount user = profile.getUserAccount();
		return new LecturerDirectoryResponse(
				profile.getId(),
				user == null ? null : user.getId(),
				user == null ? null : user.getFullName(),
				user == null ? null : user.getEmail(),
				isAssignable(user));
	}

	private static String normalizeSearch(String search) {
		if (!StringUtils.hasText(search)) {
			return null;
		}
		String trimmed = search.trim();
		if (trimmed.length() > SEARCH_MAX_LENGTH) {
			return trimmed.substring(0, SEARCH_MAX_LENGTH);
		}
		return trimmed;
	}
}
