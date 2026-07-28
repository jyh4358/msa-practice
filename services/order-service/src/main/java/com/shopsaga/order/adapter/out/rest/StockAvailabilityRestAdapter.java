package com.shopsaga.order.adapter.out.rest;

import com.shopsaga.order.application.port.in.StockPrecheck;
import com.shopsaga.order.application.port.out.StockAvailabilityPort;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * Phase 14: 재고 사전 확인 아웃바운드 어댑터 — <b>fallback(우아한 강등)이 사는 곳</b>.
 *
 * <p>{@link InventoryStockClient} 는 실패를 그대로 던지고, 이 어댑터가 그것을
 * {@link StockPrecheck.Status#UNKNOWN} 으로 <b>번역</b>한다. 둘을 분리한 이유는 두 가지다.
 * <ol>
 *   <li>Spring AOP 는 <b>자기 자신 호출(self-invocation)에 적용되지 않는다</b> — 같은 클래스 안에서
 *       {@code this.availableQuantity(...)} 를 부르면 프록시를 거치지 않아 애너테이션이 전부 무시된다.
 *       (복원력 코드가 "조용히 아무것도 안 하는" 가장 흔한 사고다.)</li>
 *   <li>fallback 을 애너테이션 안쪽에 두면 바깥 aspect 가 실패를 보지 못해 회로차단기가 학습하지 못한다.</li>
 * </ol>
 *
 * <p>토글 {@code order.stock-precheck.enabled=false} 로 통째로 끌 수 있다 —
 * 부가 기능은 언제든 꺼서 본 기능만 남길 수 있어야 한다.
 */
@Component
@ConditionalOnProperty(name = "order.stock-precheck.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
class StockAvailabilityRestAdapter implements StockAvailabilityPort {

    private final InventoryStockClient client;

    @Override
    public StockPrecheck precheck(List<Line> lines) {
        // ⚠️ 토큰은 반드시 '여기'(요청 스레드)에서 꺼낸다 — 클라이언트 본문은 다른 스레드에서 돈다.
        String bearerToken = currentBearerToken();

        StockPrecheck result = StockPrecheck.available();
        for (Line line : lines) {
            StockPrecheck one = precheckOne(line, bearerToken);
            // 확정된 부족이 가장 유용한 정보다 → 그걸 우선하고, 나머지는 UNKNOWN 이 우선한다.
            if (one.status() == StockPrecheck.Status.INSUFFICIENT) {
                return one;
            }
            if (one.status() == StockPrecheck.Status.UNKNOWN) {
                result = one;
            }
        }
        return result;
    }

    private StockPrecheck precheckOne(Line line, String bearerToken) {
        try {
            int available = client.availableQuantity(line.productId(), bearerToken).join();
            return available >= line.quantity()
                    ? StockPrecheck.available()
                    : StockPrecheck.insufficient("productId=" + line.productId()
                            + " 요청=" + line.quantity() + " 가용=" + available);
        } catch (Exception e) {
            // ★ 여기서 예외를 삼키는 것이 이 Phase 의 핵심 — 부가 기능의 실패가 주문을 막지 않는다.
            Throwable cause = unwrap(e);
            log.warn("재고 사전 확인 실패 — UNKNOWN 으로 강등 productId={} 원인={}",
                    line.productId(), describe(cause));
            return StockPrecheck.unknown(describe(cause));
        }
    }

    /**
     * 실패 원인을 짧은 이름으로 — <b>어떤 패턴이 개입했는지</b> 바로 알 수 있게.
     *
     * <p>⚠️ 반드시 ASCII 로만 만든다. 이 값은 응답 헤더 {@code X-Stock-Precheck} 에 실리는데,
     * HTTP 헤더는 기본적으로 ISO-8859-1 이라 한글을 넣으면 서블릿 컨테이너가 헤더를 <b>통째로 버린다</b>
     * (실제로 "회로 열림"을 넣었더니 헤더가 사라져 원인을 못 보는 일이 있었다). 한글 설명은 로그에만 남긴다.
     */
    private String describe(Throwable cause) {
        if (cause instanceof CallNotPermittedException) {
            return "CIRCUIT_OPEN";      // 회로 열림 — 다운스트림을 때리지 않고 즉시 실패
        }
        if (cause instanceof BulkheadFullException) {
            return "BULKHEAD_FULL";     // 동시 호출 한도 초과
        }
        if (cause instanceof RequestNotPermitted) {
            return "RATE_LIMITED";      // 초당 호출 한도 초과
        }
        if (cause instanceof TimeoutException) {
            return "TIMEOUT";           // 응답 시간 상한 초과
        }
        return cause.getClass().getSimpleName();
    }

    private Throwable unwrap(Throwable e) {
        return e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
    }

    /**
     * 현재 요청의 JWT 를 그대로 전달한다(토큰 전파). inventory-service 도 리소스 서버라
     * 익명 호출은 401 이 된다 — 서비스 간 호출에도 신원이 필요하다는 Phase 5 원칙 그대로다.
     */
    private String currentBearerToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getTokenValue() : null;
    }
}
