package com.shopsaga.auth.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * RS256 서명을 수행하는 JwtEncoder. JWKSource(개인키 포함)로 토큰을 서명한다.
 */
@Configuration
public class JwtConfig {

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        // Spring Security 6.5.x 정본 생성자. (7.0의 빌더 API는 3.5.15엔 없음)
        return new NimbusJwtEncoder(jwkSource);
    }
}
