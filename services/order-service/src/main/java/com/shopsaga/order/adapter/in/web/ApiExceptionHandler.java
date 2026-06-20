package com.shopsaga.order.adapter.in.web;

import com.shopsaga.order.application.service.OrderNotFoundException;
import com.shopsaga.order.application.service.StockNotFoundException;
import com.shopsaga.order.domain.InsufficientStockException;
import com.shopsaga.order.domain.PaymentDeclinedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 애플리케이션/도메인 예외 → HTTP 응답 변환(인바운드 어댑터의 책임).
 * Bean Validation(@Valid) 실패 400은 Spring Boot 기본 ProblemDetail 처리에 위임한다.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler({OrderNotFoundException.class, StockNotFoundException.class})
    ProblemDetail handleNotFound(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** 재고 부족 → 409 Conflict. */
    @ExceptionHandler(InsufficientStockException.class)
    ProblemDetail handleConflict(InsufficientStockException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** 결제 거절 → 402 Payment Required. */
    @ExceptionHandler(PaymentDeclinedException.class)
    ProblemDetail handlePaymentDeclined(PaymentDeclinedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, ex.getMessage());
    }

    /** 도메인 불변식 위반(예: 수량/단가 ≤ 0) → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}
