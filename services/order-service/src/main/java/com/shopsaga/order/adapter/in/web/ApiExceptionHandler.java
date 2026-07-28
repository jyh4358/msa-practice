package com.shopsaga.order.adapter.in.web;

import com.shopsaga.order.application.service.OrderNotFoundException;
import com.shopsaga.order.application.service.StockPrecheckRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 애플리케이션/도메인 예외 → HTTP 응답 변환(인바운드 어댑터의 책임).
 * Bean Validation(@Valid) 실패 400은 Spring Boot 기본 ProblemDetail 처리에 위임한다.
 *
 * <p><b>Phase 12(Saga)에서 결제 관련 핸들러가 사라졌다.</b> 결제가 동기 호출이 아니게 되어
 * 거절(402)·통신 실패(502)를 <b>HTTP 응답으로 알릴 수 없다</b> — 주문 요청은 이미 201로 끝난 뒤에
 * 결제가 진행되기 때문이다. 실패는 이제 Saga가 주문을 CANCELLED 로 만드는 것으로 표현되고,
 * 클라이언트는 <b>조회로</b> 결과를 확인한다(결과적 일관성의 대가).
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleNotFound(OrderNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** 도메인 불변식 위반(예: 수량/단가 ≤ 0) → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Phase 14: 재고 사전 확인 기반 빠른 거절 → 409(옵션 기능, 기본 꺼짐).
     * 확정 판정이 아니라 <b>참고값</b> 기준이라는 점을 응답 본문에서도 알린다.
     */
    @ExceptionHandler(StockPrecheckRejectedException.class)
    ProblemDetail handlePrecheckRejected(StockPrecheckRejectedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }
}
