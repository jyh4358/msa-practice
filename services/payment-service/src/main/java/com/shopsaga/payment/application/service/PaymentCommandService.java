package com.shopsaga.payment.application.service;

import com.shopsaga.events.PaymentChargedEvent;
import com.shopsaga.events.PaymentDeclinedEvent;
import com.shopsaga.events.commands.ChargePaymentCommand;
import com.shopsaga.events.commands.RefundPaymentCommand;
import com.shopsaga.events.commands.SagaReply;
import com.shopsaga.outbox.CommandKeys;
import com.shopsaga.payment.application.UseCase;
import com.shopsaga.payment.application.port.in.PaymentCommandUseCase;
import com.shopsaga.payment.application.port.out.LoadPaymentPort;
import com.shopsaga.payment.application.port.out.ProcessedCommandPort;
import com.shopsaga.payment.application.port.out.PublishPaymentEventPort;
import com.shopsaga.payment.application.port.out.PublishSagaReplyPort;
import com.shopsaga.payment.application.port.out.SavePaymentPort;
import com.shopsaga.payment.application.port.out.UpdatePaymentPort;
import com.shopsaga.payment.domain.Payment;
import com.shopsaga.payment.domain.PaymentDeclinedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 13: 결제 커맨드 핸들러 — 지시받은 금액을 청구하고 결과를 돌려준다.
 *
 * <p>재고와 달리 <b>한 트랜잭션</b>으로 끝난다: 거절은 도메인이 DB에 쓰기 전에 판단하므로
 * 롤백할 부수효과가 없다(부분 상태가 생기지 않는다).
 *
 * <p>중복 커맨드(타임아웃 재전송)는 <b>결제를 다시 하지 않고</b> 저장해 둔 결과로 리플라이만 재전송한다 —
 * 이것이 이중 청구를 막는 지점이다.
 */
@UseCase
@RequiredArgsConstructor
@Slf4j
class PaymentCommandService implements PaymentCommandUseCase {

    static final String CMD_CHARGE = "ChargePayment";
    /** Phase 14: 환불 보상의 결정적 dedup 키 이름(청구와 다른 키여야 서로 간섭하지 않는다). */
    static final String CMD_REFUND = "RefundPayment";

    private final SavePaymentPort savePaymentPort;
    private final LoadPaymentPort loadPaymentPort;
    private final UpdatePaymentPort updatePaymentPort;
    private final PublishSagaReplyPort publishSagaReplyPort;
    private final PublishPaymentEventPort publishPaymentEventPort;
    private final ProcessedCommandPort processedCommandPort;

    @Override
    @Transactional
    public void onChargePayment(ChargePaymentCommand command) {
        UUID commandKey = CommandKeys.of(command.sagaId(), CMD_CHARGE);
        Instant now = Instant.now();

        Optional<ProcessedCommandPort.PriorOutcome> prior = processedCommandPort.findOutcome(commandKey);
        if (prior.isPresent()) {
            // ★ 이중 청구 방지 — 다시 청구하지 않고, 조정자가 기다리는 응답만 다시 보낸다.
            //   paymentId 는 저장해 둔 값을 그대로 싣는다. null 로 보내면 조정자가 confirm 도
            //   고아 결제 보상도 못 해서 "청구됐는데 환불 불가" 상태가 된다(감사에서 발견된 결함).
            publishSagaReplyPort.reply(new SagaReply(command.sagaId(), command.orderId(),
                    prior.get().kind(), prior.get().paymentId(), command.amount(), prior.get().reason(), now));
            log.info("[커맨드] 중복 ChargePayment — 재청구 없이 리플라이 재전송 sagaId={} kind={}",
                    command.sagaId(), prior.get().kind());
            return;
        }

        try {
            Payment captured = savePaymentPort.save(Payment.capture(command.orderId(), command.amount()));

            publishSagaReplyPort.reply(new SagaReply(command.sagaId(), command.orderId(),
                    SagaReply.Kind.PAYMENT_CHARGED, captured.getId(), captured.getAmount(), null, now));
            publishPaymentEventPort.paymentCharged(new PaymentChargedEvent(
                    command.orderId(), captured.getId(), captured.getAmount(), now));
            processedCommandPort.record(commandKey, command.sagaId(), command.orderId(),
                    SagaReply.Kind.PAYMENT_CHARGED, captured.getId(), null);

            log.info("[커맨드] 결제 성공 sagaId={} orderId={} paymentId={} amount={}",
                    command.sagaId(), command.orderId(), captured.getId(), captured.getAmount());

        } catch (PaymentDeclinedException e) {
            // 거절은 예외가 아니라 업무 결과 — 리플라이로 알리고 정상 종료(재시도 루프 방지).
            publishSagaReplyPort.reply(SagaReply.failed(command.sagaId(), command.orderId(),
                    SagaReply.Kind.PAYMENT_DECLINED, e.getMessage(), now));
            publishPaymentEventPort.paymentDeclined(new PaymentDeclinedEvent(
                    command.orderId(), command.amount(), e.getMessage(), now));
            processedCommandPort.record(commandKey, command.sagaId(), command.orderId(),
                    SagaReply.Kind.PAYMENT_DECLINED, null, e.getMessage());

            log.warn("[커맨드] 결제 거절 → 거절 리플라이 sagaId={} orderId={} amount={} reason={}",
                    command.sagaId(), command.orderId(), command.amount(), e.getMessage());
        }
    }

    /**
     * Phase 14: <b>고아 결제 보상</b> — 이미 끝난 Saga 뒤에 성립한 결제를 되돌린다.
     *
     * <p>결제 row 를 지우지 않고 {@code REFUNDED} 상태로 남긴다: 돈이 움직인 사실은 사라지지 않는다
     * (보상 = rollback 이 아니라 semantic undo). 그래서 "결제 1건, 환불 1건"이 감사 기록으로 남는다.
     */
    @Override
    @Transactional
    public void onRefundPayment(RefundPaymentCommand command) {
        UUID commandKey = CommandKeys.of(command.sagaId(), CMD_REFUND);
        Instant now = Instant.now();

        Optional<ProcessedCommandPort.PriorOutcome> prior = processedCommandPort.findOutcome(commandKey);
        if (prior.isPresent()) {
            publishSagaReplyPort.reply(new SagaReply(command.sagaId(), command.orderId(),
                    prior.get().kind(), prior.get().paymentId(), null, prior.get().reason(), now));
            log.info("[커맨드] 중복 RefundPayment — 재환불 없이 리플라이 재전송 sagaId={}", command.sagaId());
            return;
        }

        Optional<Payment> found = loadPaymentPort.loadById(command.paymentId());
        if (found.isEmpty()) {
            // 되돌릴 결제가 없다 = 재시도해도 소용없다. 실패로 기록하고 조정자에게 알린 뒤 종료(무한 재배달 방지).
            replyAndRecord(command, commandKey, "환불 대상 결제 없음: " + command.paymentId(), now);
            log.error("[커맨드] 환불 대상 결제를 찾을 수 없음 sagaId={} paymentId={}",
                    command.sagaId(), command.paymentId());
            return;
        }

        Payment payment = found.get();
        if (payment.refund(now)) {
            updatePaymentPort.update(payment);
            log.warn("[커맨드] 고아 결제 환불 sagaId={} orderId={} paymentId={} amount={} 사유={}",
                    command.sagaId(), command.orderId(), payment.getId(), payment.getAmount(), command.reason());
        } else {
            // 이미 환불된 결제 — 도메인이 멱등하게 막았다. 조정자에겐 성공으로 응답한다.
            log.info("[커맨드] 이미 환불된 결제 — 변경 없음 paymentId={}", payment.getId());
        }
        replyAndRecord(command, commandKey, null, now);
    }

    private void replyAndRecord(RefundPaymentCommand command, UUID commandKey, String reason, Instant now) {
        publishSagaReplyPort.reply(new SagaReply(command.sagaId(), command.orderId(),
                SagaReply.Kind.PAYMENT_REFUNDED, command.paymentId(), null, reason, now));
        processedCommandPort.record(commandKey, command.sagaId(), command.orderId(),
                SagaReply.Kind.PAYMENT_REFUNDED, command.paymentId(), reason);
    }
}
