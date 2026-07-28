package com.shopsaga.payment.application.service;

import com.shopsaga.events.InventoryReservedEvent;
import com.shopsaga.events.PaymentChargedEvent;
import com.shopsaga.events.PaymentDeclinedEvent;
import com.shopsaga.payment.application.port.out.ProcessedMessagePort;
import com.shopsaga.payment.application.port.out.PublishPaymentEventPort;
import com.shopsaga.payment.application.port.out.SavePaymentPort;
import com.shopsaga.payment.domain.Payment;
import com.shopsaga.payment.domain.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 12: 결제의 Saga 참여 검증 — 성공/거절이 모두 <b>이벤트</b>로 표현되는지, 중복이 이중 청구를 만들지 않는지.
 */
@ExtendWith(MockitoExtension.class)
class PaymentSagaServiceTest {

    @Mock
    SavePaymentPort savePaymentPort;
    @Mock
    PublishPaymentEventPort publishPaymentEventPort;
    @Mock
    ProcessedMessagePort processedMessagePort;
    @InjectMocks
    PaymentSagaService service;
    @Captor
    ArgumentCaptor<PaymentChargedEvent> chargedCaptor;
    @Captor
    ArgumentCaptor<PaymentDeclinedEvent> declinedCaptor;

    private static final Instant NOW = Instant.parse("2026-07-18T10:00:00Z");

    private InventoryReservedEvent reserved(UUID orderId, String amount) {
        return new InventoryReservedEvent(orderId, UUID.randomUUID(), new BigDecimal(amount), NOW);
    }

    @Test
    void chargesAndPublishesCharged_onSuccess() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        when(savePaymentPort.save(any())).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            return Payment.restore(paymentId, p.getOrderId(), p.getAmount(), PaymentStatus.CAPTURED, NOW, null);
        });
        UUID messageId = UUID.randomUUID();

        service.onInventoryReserved(messageId, reserved(orderId, "30.00"));

        verify(publishPaymentEventPort).paymentCharged(chargedCaptor.capture());
        assertThat(chargedCaptor.getValue().orderId()).isEqualTo(orderId);
        assertThat(chargedCaptor.getValue().paymentId()).isEqualTo(paymentId);
        assertThat(chargedCaptor.getValue().amount()).isEqualByComparingTo("30.00");
        verify(processedMessagePort).markProcessed(messageId);
    }

    @Test
    void publishesDeclined_insteadOfThrowing_whenGatewayDeclines() {
        UUID orderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        // 가짜 게이트웨이는 합계가 .99 로 끝나면 거절한다 → 예외가 아니라 사실(이벤트)로 표현돼야 한다
        service.onInventoryReserved(messageId, reserved(orderId, "10.99"));

        verify(savePaymentPort, never()).save(any());   // 결제 row 는 만들지 않는다
        verify(publishPaymentEventPort).paymentDeclined(declinedCaptor.capture());
        assertThat(declinedCaptor.getValue().orderId()).isEqualTo(orderId);
        assertThat(declinedCaptor.getValue().amount()).isEqualByComparingTo("10.99");
        verify(publishPaymentEventPort, never()).paymentCharged(any());
        // 거절도 "처리 완료" — 재배달돼도 다시 시도하지 않는다(무한 루프 방지)
        verify(processedMessagePort).markProcessed(messageId);
    }

    @Test
    void duplicateDelivery_doesNotChargeTwice() {
        UUID messageId = UUID.randomUUID();
        when(processedMessagePort.isAlreadyProcessed(messageId)).thenReturn(true);

        service.onInventoryReserved(messageId, reserved(UUID.randomUUID(), "30.00"));

        verify(savePaymentPort, never()).save(any());   // ★ 이중 청구 방지 — 가장 중요한 가드
        verify(publishPaymentEventPort, never()).paymentCharged(any());
        verify(processedMessagePort, never()).markProcessed(messageId);
    }
}
