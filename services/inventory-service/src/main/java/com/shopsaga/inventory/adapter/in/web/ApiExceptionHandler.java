package com.shopsaga.inventory.adapter.in.web;

import com.shopsaga.inventory.application.service.StockNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 도메인 예외 → HTTP 번역(웹 어댑터 한정). */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(StockNotFoundException.class)
    ProblemDetail handleNotFound(StockNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
