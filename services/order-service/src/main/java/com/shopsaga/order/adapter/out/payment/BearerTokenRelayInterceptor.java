package com.shopsaga.order.adapter.out.payment;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;

import java.io.IOException;

/**
 * 인바운드 JWT를 payment-service 호출로 그대로 전파(토큰 릴레이).
 *
 * <p>리소스 서버 인증의 principal 은 JwtAuthenticationToken 이고 credentials 가 Jwt(AbstractOAuth2Token).
 * SecurityContextHolder 에서 원 토큰 값을 읽어 Authorization: Bearer 헤더로 붙인다.
 * order→payment 는 요청 스레드에서 동기로 일어나므로 SecurityContext 가 유효하다.
 */
class BearerTokenRelayInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getCredentials() instanceof AbstractOAuth2Token token) {
            request.getHeaders().setBearerAuth(token.getTokenValue());
        }
        return execution.execute(request, body);
    }
}
