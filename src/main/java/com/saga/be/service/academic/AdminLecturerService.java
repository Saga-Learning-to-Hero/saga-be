package com.saga.be.service.academic;

import com.saga.be.dto.academic.LecturerDirectoryPageResponse;
import com.saga.be.dto.academic.LecturerDirectoryResponse;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.service.admin.AdminPaging;
import com.saga.be.web.RequestTiming;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
		return RequestTiming.record("listLecturers", () -> lecturers
				.searchDirectory(assignable(active), AccountRole.LECTURER, AccountStatus.ACTIVE, normalizeSearch(search))
				.stream()
				.map(AdminLecturerService::toResponse)
				.toList());
	}

	@Transactional(readOnly = true)
	public LecturerDirectoryPageResponse listPaged(Boolean active, String search, Integer page, Integer size) {
		return RequestTiming.record("listLecturersPaged", () -> {
			int pageNumber = AdminPaging.page(page);
			int pageSize = AdminPaging.size(size);
			Page<LecturerProfile> result = lecturers.searchDirectoryPage(
					assignable(active),
					AccountRole.LECTURER,
					AccountStatus.ACTIVE,
					normalizeSearch(search),
					PageRequest.of(pageNumber, pageSize));
			List<LecturerDirectoryResponse> items =
					result.getContent().stream().map(AdminLecturerService::toResponse).toList();
			return new LecturerDirectoryPageResponse(items, pageNumber, pageSize, result.getTotalElements());
		});
	}

	static boolean isAssignable(UserAccount user) {
		return user != null
				&& user.getAccountRole() == AccountRole.LECTURER
				&& user.getAccountStatus() == AccountStatus.ACTIVE;
	}

	private static boolean assignable(Boolean active) {
		return active == null || active;
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
			trimmed = trimmed.substring(0, SEARCH_MAX_LENGTH);
		}
		return escapeLike(trimmed);
	}

	static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
