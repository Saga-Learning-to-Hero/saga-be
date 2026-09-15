package com.saga.be.repository;

import com.saga.be.entity.notification.FirebaseInstallation;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FirebaseInstallationRepository extends JpaRepository<FirebaseInstallation, UUID> {

	@Query("select i from FirebaseInstallation i join fetch i.ownerUser where i.firebaseInstallationId = :fid")
	Optional<FirebaseInstallation> findByFirebaseInstallationId(@Param("fid") String firebaseInstallationId);

	@Query("select i from FirebaseInstallation i join fetch i.ownerUser where i.fcmToken = :token")
	Optional<FirebaseInstallation> findByFcmToken(@Param("token") String fcmToken);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from FirebaseInstallation i join fetch i.ownerUser where i.id = :id")
	Optional<FirebaseInstallation> findByIdForUpdate(@Param("id") UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from FirebaseInstallation i join fetch i.ownerUser where i.firebaseInstallationId = :fid")
	Optional<FirebaseInstallation> findByFirebaseInstallationIdForUpdate(@Param("fid") String firebaseInstallationId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from FirebaseInstallation i join fetch i.ownerUser where i.fcmToken = :token")
	Optional<FirebaseInstallation> findByFcmTokenForUpdate(@Param("token") String fcmToken);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from FirebaseInstallation i join fetch i.ownerUser where i.id = :id and i.ownerUser.id = :ownerId")
	Optional<FirebaseInstallation> findByIdAndOwnerUser_IdForUpdate(
			@Param("id") UUID id, @Param("ownerId") UUID ownerUserId);

	long countByOwnerUser_Id(UUID ownerUserId);

	void deleteByOwnerUser_Id(UUID ownerUserId);

	@Query(
			"""
			select i from FirebaseInstallation i join fetch i.ownerUser
			where i.ownerUser.id = :ownerId
			  and i.active = true
			  and i.fcmToken is not null
			  and i.fcmToken <> ''
			""")
	List<FirebaseInstallation> findActiveWithTokenByOwnerUserId(@Param("ownerId") UUID ownerUserId);
}
