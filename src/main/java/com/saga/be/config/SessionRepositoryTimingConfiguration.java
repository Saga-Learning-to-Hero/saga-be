package com.saga.be.config;

import com.saga.be.web.RequestPhaseAttrs;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Records find/save Redis latency onto the current request (no credentials, session ids, or
 * payloads). Production wrapping uses a target-class proxy so {@code RedisIndexedSessionRepository}
 * stays injectable as itself (listener container) and {@link FindByIndexNameSessionRepository}.
 */
@Configuration
public class SessionRepositoryTimingConfiguration {

	@Bean
	static BeanPostProcessor sessionRepositoryTimingPostProcessor() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
				if (!(bean instanceof SessionRepository<?>)
						|| bean instanceof TimingSessionRepository<?>
						|| AopUtils.isAopProxy(bean)) {
					return bean;
				}
				ProxyFactory factory = new ProxyFactory(bean);
				factory.setProxyTargetClass(true);
				factory.addAdvice((MethodInterceptor) invocation -> {
					String name = invocation.getMethod().getName();
					boolean timed = "findById".equals(name) || "save".equals(name);
					if (!timed) {
						return invocation.proceed();
					}
					long started = System.nanoTime();
					try {
						return invocation.proceed();
					} finally {
						if ("findById".equals(name)) {
							accumulate(RequestPhaseAttrs.SESSION_FIND_MS, RequestPhaseAttrs.SESSION_FIND_COUNT, started);
						} else {
							accumulate(RequestPhaseAttrs.SESSION_SAVE_MS, RequestPhaseAttrs.SESSION_SAVE_COUNT, started);
						}
					}
				});
				return factory.getProxy();
			}
		};
	}

	static void accumulate(String durationAttr, String countAttr, long startedNanos) {
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

	public static class TimingSessionRepository<S extends Session> implements SessionRepository<S> {

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
	}

	/**
	 * Test-friendly indexed wrapper. Production uses a CGLIB proxy of the Redis repository instead
	 * so {@code RedisIndexedSessionRepository} remains the bean type for the message listener.
	 */
	public static final class IndexedTimingSessionRepository<S extends Session> extends TimingSessionRepository<S>
			implements FindByIndexNameSessionRepository<S> {

		private final FindByIndexNameSessionRepository<S> indexed;

		public IndexedTimingSessionRepository(FindByIndexNameSessionRepository<S> indexed) {
			super(indexed);
			this.indexed = indexed;
		}

		@Override
		public Map<String, S> findByIndexNameAndIndexValue(String indexName, String indexValue) {
			return indexed.findByIndexNameAndIndexValue(indexName, indexValue);
		}
	}
}
