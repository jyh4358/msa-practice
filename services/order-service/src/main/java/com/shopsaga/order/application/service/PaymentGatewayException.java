package com.shopsaga.order.application.service;

/** payment-service 통신 실패(연결 거부·타임아웃·5xx 등). → 502 로 전파. */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
