package com.saga.be.repository;

import com.saga.be.entity.account.StudentProfile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudentProfileRepository extends JpaRepository<StudentProfile, UUID> {

	boolean existsByStudentCode(String studentCode);

	Optional<StudentProfile> findByStudentCode(String studentCode);

	Optional<StudentProfile> findByStudentCodeIgnoreCase(String studentCode);

	Optional<StudentProfile> findByUserAccount_Id(UUID userId);

	@Query(
			"""
			SELECT p FROM StudentProfile p
			JOIN FETCH p.userAccount
			WHERE UPPER(p.studentCode) IN :codes
			""")
	List<StudentProfile> findFetchedByStudentCodeUpperIn(@Param("codes") Collection<String> codes);

	@Query(
			"""
			SELECT p FROM StudentProfile p
			JOIN FETCH p.userAccount
			WHERE p.userAccount.id IN :userIds
			""")
	List<StudentProfile> findFetchedByUserAccount_IdIn(@Param("userIds") Collection<UUID> userIds);
}
