package com.shopsaga.payment.application.service;

import com.shopsaga.events.commands.ChargePaymentCommand;
import com.shopsaga.events.commands.SagaReply;
import com.shopsaga.outbox.CommandKeys;
import com.shopsaga.payment.application.port.out.ProcessedCommandPort;
import com.shopsaga.payment.application.port.out.PublishPaymentEventPort;
import com.shopsaga.payment.application.port.out.PublishSagaReplyPort;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 13: 결제 커맨드 핸들러 검증 — 특히 <b>타임아웃 재전송이 이중 청구를 만들지 않는지</b>.
 */
@ExtendWith(MockitoExtension.class)
class PaymentCommandServiceTest {

    @Mock
    SavePaymentPort savePaymentPort;
    @Mock
    PublishSagaReplyPort publishSagaReplyPort;
    @Mock
    PublishPaymentEventPort publishPaymentEventPort;
    @Mock
    ProcessedCommandPort processedCommandPort;
    @InjectMocks
    PaymentCommandService service;
    @Captor
    ArgumentCaptor<SagaReply> replyCaptor;

    private static final Instant T0 = Instant.parse("2026-07-28T10:00:00Z");

    private ChargePaymentCommand command(UUID sagaId, String amount) {
        return new ChargePaymentCommand(sagaId, UUID.randomUUID(), new BigDecimal(amount), T0);
    }

    @Test
    void charges_andRepliesPaymentCharged() {
        UUID sagaId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        when(savePaymentPort.save(any())).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            return Payment.restore(paymentId, p.getOrderId(), p.getAmount(), PaymentStatus.CAPTURED, T0);
        });

        service.onChargePayment(command(sagaId, "30.00"));

        verify(publishSagaReplyPort).reply(replyCaptor.capture());
        assertThat(replyCaptor.getValue().kind()).isEqualTo(SagaReply.Kind.PAYMENT_CHARGED);
        assertThat(replyCaptor.getValue().paymentId()).isEqualTo(paymentId);
        verify(processedCommandPort).record(any(), eq(sagaId), any(),
                eq(SagaReply.Kind.PAYMENT_CHARGED), eq(null));
    }

    @Test
    void declinedGateway_repliesDeclined_insteadOfThrowing() {
        UUID sagaId = UUID.randomUUID();

        // 가짜 게이트웨이는 합계가 .99 로 끝나면 거절 → 예외가 아니라 리플라이로 표현돼야 한다
        service.onChargePayment(command(sagaId, "10.99"));

        verify(savePaymentPort, never()).save(any());
        verify(publishSagaReplyPort).reply(replyCaptor.capture());
        assertThat(replyCaptor.getValue().kind()).isEqualTo(SagaReply.Kind.PAYMENT_DECLINED);
        assertThat(replyCaptor.getValue().reason()).isNotBlank();
    }

    @Test
    void resentCommand_doesNotChargeTwice_butStillReplies() {
        UUID sagaId = UUID.randomUUID();
        ChargePaymentCommand cmd = command(sagaId, "30.00");
        UUID expectedKey = CommandKeys.of(sagaId, PaymentCommandService.CMD_CHARGE);
        when(processedCommandPort.findOutcome(expectedKey))
                .thenReturn(Optional.of(new ProcessedCommandPort.PriorOutcome(
                        SagaReply.Kind.PAYMENT_CHARGED, null)));

        service.onChargePayment(cmd);

        // ★ 재청구 없음 — 타임아웃 sweep 이 커맨드를 다시 보내도 돈이 두 번 빠지지 않는다.
        verify(savePaymentPort, never()).save(any());
        // ★ 그러나 응답은 다시 보낸다 — 조용히 무시하면 조정자가 영영 기다린다.
        verify(publishSagaReplyPort).reply(replyCaptor.capture());
        assertThat(replyCaptor.getValue().kind()).isEqualTo(SagaReply.Kind.PAYMENT_CHARGED);
    }

    @Test
    void commandKey_isDeterministic_soResendMatchesOriginal() {
        UUID sagaId = UUID.randomUUID();

        // 재전송은 메시지 id가 매번 달라지지만, 커맨드 키는 같아야 dedup 이 성립한다.
        assertThat(CommandKeys.of(sagaId, PaymentCommandService.CMD_CHARGE))
                .isEqualTo(CommandKeys.of(sagaId, PaymentCommandService.CMD_CHARGE));
        assertThat(CommandKeys.of(sagaId, PaymentCommandService.CMD_CHARGE))
                .isNotEqualTo(CommandKeys.of(UUID.randomUUID(), PaymentCommandService.CMD_CHARGE));
    }
}
