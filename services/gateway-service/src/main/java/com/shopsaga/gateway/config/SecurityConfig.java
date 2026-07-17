package com.shopsaga.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * 게이트웨이 = 리액티브 OAuth2 리소스 서버(엣지 인증).
 * /auth/**(로그인)·/actuator/health 는 공개, 그 외는 유효한 JWT 필요.
 *
 * <p>주의: 리액티브라 ServerHttpSecurity·@EnableWebFluxSecurity 를 쓴다(서블릿 HttpSecurity 아님).
 * CSRF 는 무상태 API라 끈다.
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex
                        .pathMatchers("/auth/**").permitAll()          // 로그인 통과(순서상 permit 먼저)
                        .pathMatchers("/actuator/health").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(RolesClaimConverter.rolesConverter())));
        return http.build();
    }
}
