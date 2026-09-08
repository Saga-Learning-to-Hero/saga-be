package com.saga.be.config;

import com.saga.be.web.RequestPhaseAttrs;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Wraps the Spring Session {@link SessionRepository} to record find/save Redis latency
 * onto the current request (no credentials, session ids, or payloads).
 */
@Configuration
public class SessionRepositoryTimingConfiguration {

	@Bean
	static BeanPostProcessor sessionRepositoryTimingPostProcessor() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
				if (bean instanceof SessionRepository<?> repository && !(bean instanceof TimingSessionRepository<?>)) {
					@SuppressWarnings({"rawtypes", "unchecked"})
					SessionRepository<?> wrapped = new TimingSessionRepository(repository);
					return wrapped;
				}
				return bean;
			}
		};
	}

	static final class TimingSessionRepository<S extends Session> implements SessionRepository<S> {

		private final SessionRepository<S> delegate;

		TimingSessionRepository(SessionRepository<S> delegate) {
			this.delegate = delegate;
		}

		@Override
		public S createSession() {
			return delegate.createSession();
		}

		@Override
		@Nullable
		public S findById(String id) {
			long started = System.nanoTime();
			try {
				return delegate.findById(id);
			} finally {
				accumulate(RequestPhaseAttrs.SESSION_FIND_MS, RequestPhaseAttrs.SESSION_FIND_COUNT, started);
			}
		}

		@Override
		public void deleteById(String id) {
			delegate.deleteById(id);
		}

		@Override
		public void save(S session) {
			long started = System.nanoTime();
			try {
				delegate.save(session);
			} finally {
				accumulate(RequestPhaseAttrs.SESSION_SAVE_MS, RequestPhaseAttrs.SESSION_SAVE_COUNT, started);
			}
		}

		private static void accumulate(String durationAttr, String countAttr, long startedNanos) {
			long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
			RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
			if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
				return;
			}
			HttpServletRequest request = servletAttributes.getRequest();
			Object priorMs = request.getAttribute(durationAttr);
			long totalMs = elapsedMs + (priorMs instanceof Long prior ? prior : 0L);
			request.setAttribute(durationAttr, totalMs);
			Object priorCount = request.getAttribute(countAttr);
			int count = 1 + (priorCount instanceof Integer prior ? prior : 0);
			request.setAttribute(countAttr, count);
		}
	}
}
