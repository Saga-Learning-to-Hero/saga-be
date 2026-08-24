package com.saga.be.entity.integration;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.IdentityMappingAction;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.integration.IdentityMap;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "identity_mapping_history",
	indexes = {
		@Index(name = "ix_identity_history_map", columnList = "identity_map_id")
	}
)
public class IdentityMappingHistory extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "identity_map_id", nullable = false)
	private IdentityMap identityMap;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_account_id", nullable = false)
	private UserAccount userAccount;

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", length = 32, nullable = false)
	private IntegrationProvider provider;

	@Column(name = "external_account_id", length = 255, nullable = false)
	private String externalAccountId;

	@Enumerated(EnumType.STRING)
	@Column(name = "action", length = 32, nullable = false)
	private IdentityMappingAction action;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "actor_user_id", nullable = true)
	private UserAccount actorUser;

	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;
}
