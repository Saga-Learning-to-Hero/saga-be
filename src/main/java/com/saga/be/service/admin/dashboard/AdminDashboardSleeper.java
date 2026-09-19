package com.saga.be.service.admin.dashboard;

import java.time.Duration;

@FunctionalInterface
public interface AdminDashboardSleeper {

	void sleep(Duration duration) throws InterruptedException;
}
