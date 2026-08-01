package com.shopsaga.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * 게이트웨이 = 리액티브 OAuth2 리소스 서버(엣지 인증).
 * /auth/**(로그인)·/actuator/health(+probe 하위경로) 는 공개, 그 외는 유효한 JWT 필요.
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
                        // Phase 16: k8s kubelet 은 probe 요청에 토큰을 붙이지 않는다.
                        //   "/actuator/health" 만 열면 /actuator/health/{liveness,readiness} 가 401 →
                        //   probe 실패 → readiness 미달로 트래픽 차단, liveness 실패로 무한 재시작.
                        .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(RolesClaimConverter.rolesConverter())));
        return http.build();
    }
}
