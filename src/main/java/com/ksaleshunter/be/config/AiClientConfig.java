package com.ksaleshunter.be.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * AI 서비스(FastAPI) 호출용 공용 클라이언트.
 *
 * analysis·product-fill·pricing/quote·copilot 프록시가 전부 이 빈을 주입받아 쓴다.
 * 각자 라우트에서 RestClient 를 새로 만들지 말 것. base-url 은 여기, 타임아웃은
 * application.yml 의 spring.http.client 에서 한 곳으로 관리한다.
 * 내부 토큰(X-Internal-Token)은 기본 헤더로 넣어 라우트가 토큰을 다루지 않게 한다.
 */
@Configuration
public class AiClientConfig {

	@Bean
	RestClient aiRestClient(RestClient.Builder builder,
			@Value("${ai.base-url}") String baseUrl,
			@Value("${ai.internal-token}") String internalToken) {
		return builder.baseUrl(baseUrl).defaultHeader("X-Internal-Token", internalToken).build();
	}
}
