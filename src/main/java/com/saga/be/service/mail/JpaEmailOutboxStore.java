package com.saga.be.service.mail;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.notification.EmailOutbox;
import com.saga.be.repository.EmailOutboxRepository;
import com.saga.be.repository.UserAccountRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!test")
public class JpaEmailOutboxStore implements EmailOutboxStore {

	private final EmailOutboxRepository emails;
	private final UserAccountRepository users;

	public JpaEmailOutboxStore(EmailOutboxRepository emails, UserAccountRepository users) {
		this.emails = emails;
		this.users = users;
	}

	@Override
	public EmailOutbox save(EmailOutbox row) {
		return emails.save(row);
	}

	@Override
	public Optional<EmailOutbox> findById(UUID id) {
		return emails.findById(id);
	}

	@Override
	public Optional<UserAccount> findUser(UUID userId) {
		return users.findById(userId);
	}

	@Override
	public List<UUID> findClaimableIds(LocalDateTime now, LocalDateTime staleBefore, int limit) {
		return emails.findClaimableIds(now, staleBefore, PageRequest.of(0, Math.max(1, limit)));
	}

	@Override
	@Transactional
	public boolean claim(UUID id, LocalDateTime now, LocalDateTime staleBefore) {
		return emails.claim(id, now, staleBefore) == 1;
	}
}
