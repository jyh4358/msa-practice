package com.shopsaga.order.adapter.out.payment;

import com.shopsaga.order.application.port.out.PaymentGatewayPort;
import com.shopsaga.order.application.service.PaymentDeclinedException;
import com.shopsaga.order.application.service.PaymentGatewayException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 아웃바운드 결제 어댑터: payment-service 를 동기 REST 로 호출(PaymentGatewayPort 구현).
 * 402 → PaymentDeclinedException, 그 외 통신 실패 → PaymentGatewayException.
 */
@Component
@RequiredArgsConstructor
class PaymentGatewayRestAdapter implements PaymentGatewayPort {

    private final RestClient paymentRestClient;

    @Override
    public UUID capture(UUID orderId, BigDecimal amount) {
        try {
            PaymentResponse response = paymentRestClient.post()
                    .uri("/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PaymentRequest(orderId, amount))
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.PAYMENT_REQUIRED.value(),
                            (request, res) -> {
                                throw new PaymentDeclinedException(amount);
                            })
                    .body(PaymentResponse.class);
            if (response == null) {
                throw new PaymentGatewayException("payment-service 응답이 비어 있음", null);
            }
            return response.paymentId();
        } catch (PaymentDeclinedException e) {
            throw e;
        } catch (RestClientException e) {
            // 연결 거부·타임아웃·4xx/5xx 등 → 통신 실패로 변환(주문 트랜잭션 롤백 유발)
            throw new PaymentGatewayException("payment-service 호출 실패: " + e.getMessage(), e);
        }
    }

    record PaymentRequest(UUID orderId, BigDecimal amount) {
    }

    record PaymentResponse(UUID paymentId, UUID orderId, BigDecimal amount, String status, Instant capturedAt) {
    }
}
