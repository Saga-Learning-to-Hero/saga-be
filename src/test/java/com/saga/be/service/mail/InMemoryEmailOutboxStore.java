package com.saga.be.service.mail;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.EmailDeliveryStatus;
import com.saga.be.entity.notification.EmailOutbox;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryEmailOutboxStore implements EmailOutboxStore {

	final Map<UUID, EmailOutbox> rows = new ConcurrentHashMap<>();
	final Map<UUID, UserAccount> users = new ConcurrentHashMap<>();

	@Override
	public EmailOutbox save(EmailOutbox row) {
		if (row.getId() == null) {
			row.setId(UUID.randomUUID());
		}
		if (row.getCreatedAt() == null) {
			row.setCreatedAt(LocalDateTime.now());
		}
		row.setUpdatedAt(LocalDateTime.now());
		rows.put(row.getId(), row);
		return row;
	}

	@Override
	public Optional<EmailOutbox> findById(UUID id) {
		return Optional.ofNullable(rows.get(id));
	}

	@Override
	public Optional<UserAccount> findUser(UUID userId) {
		return Optional.ofNullable(users.get(userId));
	}

	@Override
	public List<UUID> findClaimableIds(LocalDateTime now, LocalDateTime staleBefore, int limit) {
		return rows.values().stream()
				.filter(row -> claimable(row, now, staleBefore))
				.sorted(Comparator.comparing(EmailOutbox::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
				.map(EmailOutbox::getId)
				.limit(Math.max(1, limit))
				.toList();
	}

	@Override
	public boolean claim(UUID id, LocalDateTime now, LocalDateTime staleBefore) {
		synchronized (this) {
			EmailOutbox row = rows.get(id);
			if (row == null || !claimable(row, now, staleBefore)) {
				return false;
			}
			row.setDeliveryStatus(EmailDeliveryStatus.PROCESSING);
			row.setLastAttemptAt(now);
			int attempts = row.getAttemptCount() == null ? 0 : row.getAttemptCount();
			row.setAttemptCount(attempts + 1);
			return true;
		}
	}

	private static boolean claimable(EmailOutbox row, LocalDateTime now, LocalDateTime staleBefore) {
		if (row.getDeliveryStatus() == EmailDeliveryStatus.PENDING
				&& (row.getScheduledAt() == null || !row.getScheduledAt().isAfter(now))) {
			return true;
		}
		return row.getDeliveryStatus() == EmailDeliveryStatus.PROCESSING
				&& row.getLastAttemptAt() != null
				&& !row.getLastAttemptAt().isAfter(staleBefore);
	}

	List<EmailOutbox> all() {
		return new ArrayList<>(rows.values());
	}
}
