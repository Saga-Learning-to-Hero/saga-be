package com.saga.be.repository;

import com.saga.be.entity.academic.ActiveSemesterSetting;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActiveSemesterSettingRepository extends JpaRepository<ActiveSemesterSetting, Byte> {

	@Query(
			"""
			select s from ActiveSemesterSetting s
			left join fetch s.semester
			where s.singletonId = :id
			""")
	Optional<ActiveSemesterSetting> findByIdFetchSemester(@Param("id") Byte id);
}
