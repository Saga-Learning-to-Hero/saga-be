package com.saga.be.repository;

import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.SubjectStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubjectRepository extends JpaRepository<Subject, UUID> {

	Optional<Subject> findBySubjectCode(String subjectCode);

	boolean existsBySubjectCode(String subjectCode);

	@Query(
			"""
			select s from Subject s
			where (:code is null or s.subjectCode = :code)
			  and (:status is null or s.status = :status)
			  and (:q is null
			    or lower(s.subjectCode) like lower(concat('%', :q, '%'))
			    or lower(s.name) like lower(concat('%', :q, '%'))
			    or lower(coalesce(s.nameVietnamese, '')) like lower(concat('%', :q, '%')))
			order by s.subjectCode
			""")
	List<Subject> search(
			@Param("code") String code, @Param("status") SubjectStatus status, @Param("q") String q);
}
