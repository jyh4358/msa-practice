package com.shopsaga.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.UUID;

/**
 * RS256 서명 키. 개인키로 서명하고 공개키는 JWKS(/oauth2/jwks)로 노출한다.
 *
 * <p><b>Phase 16b 에서 바뀐 것 — 키를 '설정'으로 뺐다.</b>
 * Phase 5~16a 에서는 기동할 때마다 새 키쌍을 만들었다. 그래서 두 가지 제약이 있었다.
 * <ol>
 *   <li>재시작하면 kid 가 바뀌어 이전에 발급한 토큰이 전부 무효가 된다(재로그인 필요).</li>
 *   <li><b>복제본을 2 이상으로 못 올린다</b> — 파드마다 키가 달라, A 가 발급한 토큰을
 *       리소스 서버가 (캐시한 JWKS 가 B 것이면) 검증하지 못해 산발적 401 이 난다.
 *       Phase 16a 의 알려진 한계 #4 가 이것이었다.</li>
 * </ol>
 *
 * <p>이제 {@code auth.jwt.private-key} 가 주어지면 그 키를 쓴다(모든 인스턴스가 같은 키 → 복제 가능).
 * 값이 비어 있으면 예전처럼 임시 키를 만든다 — 로컬 개발에서 키를 준비하지 않아도 바로 뜨게 하기 위함이다.
 *
 * <p>키는 <b>ConfigMap 이 아니라 Secret</b> 으로 주입한다(k8s: {@code AUTH_JWT_PRIVATE_KEY} 환경변수).
 * 공개키는 개인키에서 유도하므로 따로 보관하지 않는다.
 */
@Configuration
public class RsaKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(RsaKeyConfig.class);

    @Bean
    public RSAKey rsaKey(@Value("${auth.jwt.private-key:}") String privateKeyPem,
                         @Value("${auth.jwt.key-id:}") String keyId) throws Exception {
        String kid = StringUtils.hasText(keyId) ? keyId : UUID.randomUUID().toString();

        if (!StringUtils.hasText(privateKeyPem)) {
            log.warn("auth.jwt.private-key 가 비어 있다 — 임시 키쌍을 생성한다(kid={}). "
                    + "이 인스턴스가 발급한 토큰은 재시작 시 무효가 되고, 복제본을 늘리면 검증이 깨진다.", kid);
            return generateEphemeral(kid);
        }

        RSAPrivateCrtKey privateKey = readPkcs8(privateKeyPem);
        // 공개키를 따로 받지 않는다 — CRT 개인키가 modulus 와 publicExponent 를 이미 들고 있다.
        RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));

        log.info("auth.jwt.private-key 로 서명 키를 로드했다(kid={}). 모든 인스턴스가 같은 키를 쓴다.", kid);
        return new RSAKey.Builder(publicKey).privateKey((RSAPrivateKey) privateKey).keyID(kid).build();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey rsaKey) {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    private static RSAKey generateEphemeral(String kid) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var keyPair = gen.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(kid)
                .build();
    }

    /**
     * PKCS#8 PEM 을 읽는다. PEM 헤더/푸터와 모든 공백(줄바꿈 포함)을 제거하고 base64 디코딩한다.
     *
     * <p>⚠️ 환경변수로 넘어온 PEM 은 줄바꿈이 {@code \n} 문자 그대로일 수도, 실제 개행일 수도 있다.
     * 어느 쪽이든 통과하도록 <b>모든 공백류를 지운 뒤</b> 디코딩한다.
     * ⚠️ PKCS#1({@code BEGIN RSA PRIVATE KEY})은 지원하지 않는다 —
     * {@code openssl pkcs8 -topk8 -nocrypt} 로 변환할 것.
     */
    private static RSAPrivateCrtKey readPkcs8(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
