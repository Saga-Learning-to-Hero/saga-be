package com.saga.be.repository;

import com.saga.be.entity.academic.AcademicClass;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AcademicClassRepository extends JpaRepository<AcademicClass, UUID> {

	boolean existsBySemester_IdAndClassCode(UUID semesterId, String classCode);

	boolean existsBySemester_IdAndClassCodeAndIdNot(UUID semesterId, String classCode, UUID id);

	boolean existsBySemester_Id(UUID semesterId);

	@Query(
			"""
			SELECT c FROM AcademicClass c
			LEFT JOIN FETCH c.semester
			WHERE c.deletedAt IS NULL
			ORDER BY c.classCode ASC
			""")
	List<AcademicClass> findByDeletedAtIsNullOrderByClassCodeAsc();

	@Query(
			"""
			SELECT c FROM AcademicClass c
			LEFT JOIN FETCH c.semester
			WHERE c.semester.id = :semesterId AND c.deletedAt IS NULL
			ORDER BY c.classCode ASC
			""")
	List<AcademicClass> findBySemester_IdAndDeletedAtIsNullOrderByClassCodeAsc(@Param("semesterId") UUID semesterId);
}
