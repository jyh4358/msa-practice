package com.shopsaga.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * 부팅 시 RSA 2048 keypair 생성(kid = UUID). 개인키로 서명, 공개키는 JWKS로 노출.
 *
 * <p>주의: 인메모리 keypair라 auth-service 재시작 시 kid가 바뀐다 → 재시작 전 발급된 토큰은
 * 검증 불가(=재로그인 필요). 학습용으로는 OK(운영은 Phase 7에서 keystore 고정 고려).
 */
@Configuration
public class RsaKeyConfig {

    @Bean
    public RSAKey rsaKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var keyPair = gen.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey rsaKey) {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }
}
