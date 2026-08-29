package com.saga.be.service.mail;

import com.saga.be.mail.EmailFailureCodes;
import com.saga.be.mail.EmailMessage;
import com.saga.be.mail.EmailSendException;
import com.saga.be.mail.EmailSender;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class EmailOutboxWorker {

	private static final Logger log = LoggerFactory.getLogger(EmailOutboxWorker.class);

	private final EmailOutboxService outbox;
	private final EmailSender sender;

	public EmailOutboxWorker(EmailOutboxService outbox, EmailSender sender) {
		this.outbox = outbox;
		this.sender = sender;
	}

	@Scheduled(fixedDelayString = "${saga.mail.worker-delay-ms:15000}")
	public void processDue() {
		processBatch();
	}

	public int processBatch() {
		if (!sender.isEnabled()) {
			return 0;
		}
		int sent = 0;
		for (UUID id : outbox.findClaimableIds()) {
			if (processOne(id)) {
				sent++;
			}
		}
		return sent;
	}

	boolean processOne(UUID id) {
		if (!outbox.claim(id)) {
			return false;
		}
		EmailMessage message = outbox.render(id);
		if (message == null) {
			outbox.markFailure(id, EmailFailureCodes.UNKNOWN);
			return false;
		}
		try {
			sender.send(message);
			outbox.markSent(id);
			return true;
		} catch (EmailSendException ex) {
			log.warn("email worker result=failure category={} idPresent=true", ex.getFailureCode());
			outbox.markFailure(id, EmailFailureCodes.from(ex));
			return false;
		} catch (RuntimeException ex) {
			String code = EmailFailureCodes.from(ex);
			log.warn("email worker result=failure category={} idPresent=true", code);
			outbox.markFailure(id, code);
			return false;
		}
	}
}
