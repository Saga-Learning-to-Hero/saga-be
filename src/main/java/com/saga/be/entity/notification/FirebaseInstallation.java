package com.saga.be.entity.notification;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.PushPlatform;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "firebase_installation",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_firebase_installation_fid", columnNames = {"firebase_installation_id"}),
		@UniqueConstraint(name = "uk_firebase_installation_fcm_token", columnNames = {"fcm_token"})
	},
	indexes = {
		@Index(name = "ix_firebase_owner", columnList = "owner_user_id, active")
	}
)
public class FirebaseInstallation extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_user_id", nullable = false)
	private UserAccount ownerUser;

	@Column(name = "firebase_installation_id", length = 255, nullable = false)
	private String firebaseInstallationId;

	@Column(name = "fcm_token", length = 512)
	private String fcmToken;

	@Enumerated(EnumType.STRING)
	@Column(name = "platform", length = 16)
	private PushPlatform platform;

	@Column(name = "active", nullable = false)
	private Boolean active;

	@Column(name = "last_registered_at", nullable = false)
	private LocalDateTime lastRegisteredAt;

	@Column(name = "revoked_at")
	private LocalDateTime revokedAt;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;
}
