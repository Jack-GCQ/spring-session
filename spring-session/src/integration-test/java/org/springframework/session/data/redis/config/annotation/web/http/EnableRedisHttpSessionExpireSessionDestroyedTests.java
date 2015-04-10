/*
 * Copyright 2002-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.springframework.session.data.redis.config.annotation.web.http;


import static org.fest.assertions.Assertions.assertThat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.ExpiringSession;
import org.springframework.session.SessionRepository;
import org.springframework.session.events.SessionDestroyedEvent;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;

import sample.EnableEmbeddedRedis;
import sample.RedisServerPort;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration
@WebAppConfiguration
public class EnableRedisHttpSessionExpireSessionDestroyedTests<S extends ExpiringSession> {
	@Autowired
	private SessionRepository<S> repository;

	@Autowired
	private SessionDestroyedEventRegistry registry;

	private final Object lock = new Object();

	@Before
	public void setup() {
		registry.setLock(lock);
	}

	@Test
	public void expireFiresSessionDestroyedEvent() throws InterruptedException {
		S toSave = repository.createSession();
		toSave.setAttribute("a", "b");
		Authentication toSaveToken = new UsernamePasswordAuthenticationToken("user","password", AuthorityUtils.createAuthorityList("ROLE_USER"));
		SecurityContext toSaveContext = SecurityContextHolder.createEmptyContext();
		toSaveContext.setAuthentication(toSaveToken);
		toSave.setAttribute("SPRING_SECURITY_CONTEXT", toSaveContext);

		repository.save(toSave);

		synchronized (lock) {
			lock.wait((toSave.getMaxInactiveIntervalInSeconds() * 1000) + 1);
		}
		if(!registry.receivedEvent()) {
			// Redis makes no guarantees on when an expired event will be fired
			// we can ensure it gets fired by trying to get the session
			repository.getSession(toSave.getId());
			synchronized (lock) {
				if(!registry.receivedEvent()) {
					// wait at most second to process the event
					lock.wait(1000);
				}
			}
		}
		assertThat(registry.receivedEvent()).isTrue();
	}

	static class SessionDestroyedEventRegistry implements ApplicationListener<SessionDestroyedEvent> {
		private boolean receivedEvent;
		private Object lock;

		public void onApplicationEvent(SessionDestroyedEvent event) {
			synchronized (lock) {
				receivedEvent = true;
				lock.notifyAll();
			}
		}

		public boolean receivedEvent() {
			return receivedEvent;
		}

		public void setLock(Object lock) {
			this.lock = lock;
		}
	}

	@Configuration
	@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1)
	@EnableEmbeddedRedis
	static class Config {
		@Bean
		public JedisConnectionFactory connectionFactory(@RedisServerPort int port) throws Exception {
			JedisConnectionFactory factory = new JedisConnectionFactory();
			factory.setPort(port);
			factory.setUsePool(false);
			return factory;
		}

		@Bean
		public SessionDestroyedEventRegistry sessionDestroyedEventRegistry() {
			return new SessionDestroyedEventRegistry();
		}
	}
}
