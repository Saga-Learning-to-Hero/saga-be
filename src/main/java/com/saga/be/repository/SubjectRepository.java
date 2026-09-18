package com.saga.be.repository;

import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.SubjectStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubjectRepository extends JpaRepository<Subject, UUID> {

	Optional<Subject> findBySubjectCode(String subjectCode);

	boolean existsBySubjectCode(String subjectCode);

	/**
	 * Admin subject directory. {@code q} is a LIKE fragment (wildcards already escaped by the
	 * caller). Callers must pass an unsorted {@link Pageable}; sort is in JPQL.
	 */
	@Query(
			value =
					"""
					select s from Subject s
					where (:code is null or s.subjectCode = :code)
					  and (:status is null or s.status = :status)
					  and (:q is null
					    or lower(s.subjectCode) like lower(concat('%', :q, '%')) escape '\\'
					    or lower(s.name) like lower(concat('%', :q, '%')) escape '\\'
					    or lower(coalesce(s.nameVietnamese, '')) like lower(concat('%', :q, '%')) escape '\\')
					order by s.subjectCode asc, s.id asc
					""",
			countQuery =
					"""
					select count(s.id)
					from Subject s
					where (:code is null or s.subjectCode = :code)
					  and (:status is null or s.status = :status)
					  and (:q is null
					    or lower(s.subjectCode) like lower(concat('%', :q, '%')) escape '\\'
					    or lower(s.name) like lower(concat('%', :q, '%')) escape '\\'
					    or lower(coalesce(s.nameVietnamese, '')) like lower(concat('%', :q, '%')) escape '\\')
					""")
	Page<Subject> search(
			@Param("code") String code,
			@Param("status") SubjectStatus status,
			@Param("q") String q,
			Pageable pageable);
}
