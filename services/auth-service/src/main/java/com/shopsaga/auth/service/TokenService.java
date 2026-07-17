package com.shopsaga.auth.service;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * RS256 JWT 발급. roles 는 접두사 없는 순수 역할명(예: ["USER","ADMIN"])으로 담는다.
 * ROLE_ 접두사는 리소스 서버(gateway/order/payment)의 컨버터가 검증 시 부여한다.
 */
@Service
public class TokenService {

    private static final long EXPIRY_MINUTES = 60L;

    private final JwtEncoder jwtEncoder;

    public TokenService(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    public record TokenResult(String token, Instant expiresAt) {
    }

    public TokenResult issue(String subject, List<String> roles) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(EXPIRY_MINUTES, ChronoUnit.MINUTES);

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("http://auth-service")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("roles", roles)
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResult(token, expiresAt);
    }
}
