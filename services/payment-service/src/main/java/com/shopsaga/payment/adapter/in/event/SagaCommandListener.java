package com.shopsaga.payment.adapter.in.event;

import com.shopsaga.events.Topics;
import com.shopsaga.events.commands.ChargePaymentCommand;
import com.shopsaga.payment.application.port.in.PaymentCommandUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Phase 13: 조정자의 결제 지시를 받는 인바운드 어댑터.
 * 멱등성은 커맨드 키로 판단한다(유스케이스 내부) — 재전송 시 메시지 id가 바뀌기 때문.
 */
@Component
@ConditionalOnProperty(name = "saga.mode", havingValue = "orchestration", matchIfMissing = true)
@KafkaListener(topics = Topics.SAGA_COMMANDS, groupId = "payment-service")
@RequiredArgsConstructor
@Slf4j
class SagaCommandListener {

    private final PaymentCommandUseCase paymentCommandUseCase;

    @KafkaHandler
    void onChargePayment(ChargePaymentCommand command) {
        log.info("[커맨드] ChargePayment 수신 sagaId={} orderId={} amount={}",
                command.sagaId(), command.orderId(), command.amount());
        paymentCommandUseCase.onChargePayment(command);
    }

    @KafkaHandler(isDefault = true)
    void onUnknown(Object command) {
        // ReserveStock/ReleaseStock 은 inventory 에게 간 지시 — 결제는 무시한다.
        log.debug("내 커맨드가 아님 — 무시 type={}", command.getClass().getSimpleName());
    }
}
