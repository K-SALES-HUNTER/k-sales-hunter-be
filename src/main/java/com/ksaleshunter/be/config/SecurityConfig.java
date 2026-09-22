package com.ksaleshunter.be.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 레포 골격 단계의 보안 설정.
 *
 * 지금은 모든 요청을 통과시킨다. 각자 라우트를 붙여 FE 와 연동 테스트를 할 수 있게 하는 것이 목적이다.
 * JWT 검증 필터는 auth 작업에서 이 체인에 끼운다(아래 TODO 위치).
 */
@Configuration
public class SecurityConfig {

	private final List<String> allowedOrigins;

	public SecurityConfig(@Value("${cors.allowed-origins}") List<String> allowedOrigins) {
		this.allowedOrigins = allowedOrigins;
	}

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		return http
			.csrf(csrf -> csrf.disable())
			.cors(cors -> cors.configurationSource(corsConfigurationSource()))
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.httpBasic(basic -> basic.disable())
			.formLogin(form -> form.disable())
			// TODO(auth): addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
			//             그리고 /auth/**, /actuator/** 만 permitAll 로 좁힌다.
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(allowedOrigins);
		config.setAllowedMethods(List.of(
			HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.PUT.name(),
			HttpMethod.PATCH.name(), HttpMethod.DELETE.name(), HttpMethod.OPTIONS.name()));
		config.setAllowedHeaders(List.of("*"));
		config.setAllowCredentials(true);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}
