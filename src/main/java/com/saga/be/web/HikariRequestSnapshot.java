package com.saga.be.web;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Snapshots Hikari pool gauges onto the request — no SQL payloads. */
@Component
public class HikariRequestSnapshot {

	private final ObjectProvider<DataSource> dataSources;

	public HikariRequestSnapshot(ObjectProvider<DataSource> dataSources) {
		this.dataSources = dataSources;
	}

	public void capture(HttpServletRequest request) {
		DataSource dataSource = dataSources.getIfAvailable();
		if (!(dataSource instanceof HikariDataSource hikari)) {
			return;
		}
		HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
		if (pool == null) {
			return;
		}
		request.setAttribute(RequestPhaseAttrs.HIKARI_ACTIVE, pool.getActiveConnections());
		request.setAttribute(RequestPhaseAttrs.HIKARI_IDLE, pool.getIdleConnections());
		request.setAttribute(RequestPhaseAttrs.HIKARI_PENDING, pool.getThreadsAwaitingConnection());
	}
}
