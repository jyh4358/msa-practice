package com.shopsaga.gateway.resilience;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 14: 회로가 열렸을 때 게이트웨이가 <b>대신 돌려주는 응답</b>.
 *
 * <p>fallback 은 "성공인 척"이 아니라 <b>정직한 실패</b>여야 한다. 503 + {@code Retry-After} 로
 * "지금은 안 되니 잠시 뒤 다시" 라는 뜻을 명확히 전한다 — 클라이언트가 재시도 여부를 판단할 수 있어야 한다.
 *
 * <p>{@code forward:/fallback/{service}} 로 들어오므로 GET/POST 등 <b>모든 메서드</b>를 받아야 한다.
 */
@RestController
class FallbackController {

    @RequestMapping("/fallback/{service}")
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ProblemDetail fallback(@PathVariable String service) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                service + " 를 지금 사용할 수 없습니다(회로 열림 또는 응답 지연). 잠시 후 다시 시도해 주세요.");
        problem.setTitle("일시적으로 사용할 수 없음");
        problem.setProperty("service", service);
        problem.setProperty("hint", "Phase 14 회로차단기 fallback — 다운스트림을 더 때리지 않고 즉시 응답한다.");
        return problem;
    }
}
