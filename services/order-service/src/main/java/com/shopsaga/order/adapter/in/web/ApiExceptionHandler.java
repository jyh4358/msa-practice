package com.shopsaga.order.adapter.in.web;

import com.shopsaga.order.application.service.OrderNotFoundException;
import com.shopsaga.order.application.service.PaymentDeclinedException;
import com.shopsaga.order.application.service.PaymentGatewayException;
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

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleNotFound(OrderNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** 원격 결제 거절 → 402 Payment Required. */
    @ExceptionHandler(PaymentDeclinedException.class)
    ProblemDetail handlePaymentDeclined(PaymentDeclinedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, ex.getMessage());
    }

    /** payment-service 통신 실패 → 502 Bad Gateway. */
    @ExceptionHandler(PaymentGatewayException.class)
    ProblemDetail handleGatewayError(PaymentGatewayException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    /** 도메인 불변식 위반(예: 수량/단가 ≤ 0) → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}
