package com.shopsaga.payment.application.service;

import com.shopsaga.events.commands.ChargePaymentCommand;
import com.shopsaga.events.commands.RefundPaymentCommand;
import com.shopsaga.events.commands.SagaReply;
import com.shopsaga.outbox.CommandKeys;
import com.shopsaga.payment.application.port.out.LoadPaymentPort;
import com.shopsaga.payment.application.port.out.ProcessedCommandPort;
import com.shopsaga.payment.application.port.out.PublishPaymentEventPort;
import com.shopsaga.payment.application.port.out.PublishSagaReplyPort;
import com.shopsaga.payment.application.port.out.SavePaymentPort;
import com.shopsaga.payment.application.port.out.UpdatePaymentPort;
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
    LoadPaymentPort loadPaymentPort;
    @Mock
    UpdatePaymentPort updatePaymentPort;
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
            return Payment.restore(paymentId, p.getOrderId(), p.getAmount(), PaymentStatus.CAPTURED, T0, null);
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

    // ─────────────────────────── Phase 14: 고아 결제 보상(환불) ───────────────────────────

    private RefundPaymentCommand refundCommand(UUID sagaId, UUID paymentId) {
        return new RefundPaymentCommand(sagaId, UUID.randomUUID(), paymentId, "Saga 종료 후 도착한 결제", T0);
    }

    @Test
    void refund_marksPaymentRefunded_andReplies() {
        UUID sagaId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Payment captured = Payment.restore(paymentId, UUID.randomUUID(), new BigDecimal("30.00"),
                PaymentStatus.CAPTURED, T0, null);
        when(processedCommandPort.findOutcome(any())).thenReturn(Optional.empty());
        when(loadPaymentPort.loadById(paymentId)).thenReturn(Optional.of(captured));

        service.onRefundPayment(refundCommand(sagaId, paymentId));

        // row 를 지우는 게 아니라 REFUNDED 상태로 남긴다(semantic undo — 감사 기록 보존).
        ArgumentCaptor<Payment> updated = ArgumentCaptor.forClass(Payment.class);
        verify(updatePaymentPort).update(updated.capture());
        assertThat(updated.getValue().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(updated.getValue().getRefundedAt()).isNotNull();

        verify(publishSagaReplyPort).reply(replyCaptor.capture());
        assertThat(replyCaptor.getValue().kind()).isEqualTo(SagaReply.Kind.PAYMENT_REFUNDED);
    }

    @Test
    void resentRefundCommand_doesNotRefundTwice_butStillReplies() {
        UUID sagaId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        when(processedCommandPort.findOutcome(CommandKeys.of(sagaId, PaymentCommandService.CMD_REFUND)))
                .thenReturn(Optional.of(new ProcessedCommandPort.PriorOutcome(SagaReply.Kind.PAYMENT_REFUNDED, null)));

        service.onRefundPayment(refundCommand(sagaId, paymentId));

        verify(loadPaymentPort, never()).loadById(any());
        verify(updatePaymentPort, never()).update(any());
        // ⚠️ 그래도 리플라이는 다시 보낸다 — 무시하면 조정자가 영영 기다린다.
        verify(publishSagaReplyPort).reply(replyCaptor.capture());
        assertThat(replyCaptor.getValue().kind()).isEqualTo(SagaReply.Kind.PAYMENT_REFUNDED);
    }

    @Test
    void refund_whenAlreadyRefunded_isNoOp_butReplies() {
        UUID sagaId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Payment already = Payment.restore(paymentId, UUID.randomUUID(), new BigDecimal("30.00"),
                PaymentStatus.REFUNDED, T0, T0);
        when(processedCommandPort.findOutcome(any())).thenReturn(Optional.empty());
        when(loadPaymentPort.loadById(paymentId)).thenReturn(Optional.of(already));

        service.onRefundPayment(refundCommand(sagaId, paymentId));

        verify(updatePaymentPort, never()).update(any());
        verify(publishSagaReplyPort).reply(any());
    }

    @Test
    void refund_whenPaymentMissing_repliesWithReason_insteadOfLoopingForever() {
        UUID sagaId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        when(processedCommandPort.findOutcome(any())).thenReturn(Optional.empty());
        when(loadPaymentPort.loadById(paymentId)).thenReturn(Optional.empty());

        service.onRefundPayment(refundCommand(sagaId, paymentId));

        verify(publishSagaReplyPort).reply(replyCaptor.capture());
        assertThat(replyCaptor.getValue().kind()).isEqualTo(SagaReply.Kind.PAYMENT_REFUNDED);
        assertThat(replyCaptor.getValue().reason()).contains("환불 대상 결제 없음");
    }
}
