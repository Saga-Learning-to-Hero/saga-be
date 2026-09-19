package com.saga.be.service.admin.dashboard;

import com.saga.be.dto.admin.dashboard.AdminDashboardIntegrationPulseResponse;
import java.util.List;

@FunctionalInterface
public interface AdminDashboardPulseLoader {

	List<AdminDashboardIntegrationPulseResponse> load(boolean forceRefresh);
}
