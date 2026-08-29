package com.saga.be.service.mail;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.notification.EmailOutbox;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailOutboxStore {

	EmailOutbox save(EmailOutbox row);

	Optional<EmailOutbox> findById(UUID id);

	Optional<UserAccount> findUser(UUID userId);

	List<UUID> findClaimableIds(LocalDateTime now, LocalDateTime staleBefore, int limit);

	boolean claim(UUID id, LocalDateTime now, LocalDateTime staleBefore);
}
