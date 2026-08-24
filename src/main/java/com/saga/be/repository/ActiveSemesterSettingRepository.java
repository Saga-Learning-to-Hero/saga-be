package com.saga.be.repository;

import com.saga.be.entity.academic.ActiveSemesterSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActiveSemesterSettingRepository extends JpaRepository<ActiveSemesterSetting, Byte> {
}
