package com.saga.be.repository;

import com.saga.be.entity.academic.AcademicClass;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AcademicClassRepository extends JpaRepository<AcademicClass, UUID> {

	boolean existsBySemester_IdAndClassCode(UUID semesterId, String classCode);

	boolean existsBySemester_IdAndClassCodeAndIdNot(UUID semesterId, String classCode, UUID id);

	boolean existsBySemester_Id(UUID semesterId);

	/**
	 * Admin class directory. Callers must pass an unsorted {@link Pageable}; sort is in JPQL.
	 * Semester is a to-one fetch, so Pageable stays database-backed.
	 */
	@Query(
			value =
					"""
					select c from AcademicClass c
					left join fetch c.semester
					where c.deletedAt is null
					  and (:semesterId is null or c.semester.id = :semesterId)
					order by c.classCode asc, c.id asc
					""",
			countQuery =
					"""
					select count(c.id)
					from AcademicClass c
					where c.deletedAt is null
					  and (:semesterId is null or c.semester.id = :semesterId)
					""")
	Page<AcademicClass> findPage(@Param("semesterId") UUID semesterId, Pageable pageable);
}
