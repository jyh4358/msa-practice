package com.shopsaga.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 인증 서버 (Phase 5) — RS256 JWT 발급 + JWKS 노출.
 *
 * <p>POST /auth/login 으로 사용자 검증 후 서명된 JWT(roles 클레임 포함)를 발급하고,
 * 공개키를 GET /oauth2/jwks(JWKS)로 노출한다. 게이트웨이/order/payment 는 이 JWKS로 토큰을 검증한다.
 * Eureka 클라이언트로 등록되어 게이트웨이가 lb://auth-service 로 라우팅한다.
 */
@SpringBootApplication
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
