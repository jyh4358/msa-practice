package com.shopsaga.orderquery.adapter.in.web;

import com.shopsaga.orderquery.application.service.OrderViewNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 애플리케이션 예외 → HTTP 상태 변환(다른 서비스와 동일 컨벤션). */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(OrderViewNotFoundException.class)
    ProblemDetail handleNotFound(OrderViewNotFoundException e) {
        // 404 — 단, "아직 투영 안 됨"일 수도 있음(결과적 일관성). 메시지로 그 뜻을 남긴다.
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
