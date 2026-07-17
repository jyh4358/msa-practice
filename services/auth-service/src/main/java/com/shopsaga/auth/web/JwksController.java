package com.shopsaga.auth.web;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GET /oauth2/jwks — 공개키만 노출(JWKS). toPublicJWKSet() 이 개인키 성분(d,p,q,dp,dq,qi)을 제거한다.
 * 리소스 서버들이 spring.security.oauth2.resourceserver.jwt.jwk-set-uri 로 이 엔드포인트를 가리킨다.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final RSAKey rsaKey;

    @GetMapping("/oauth2/jwks")
    public Map<String, Object> jwks() {
        return new JWKSet(rsaKey).toPublicJWKSet().toJSONObject();
    }
}
