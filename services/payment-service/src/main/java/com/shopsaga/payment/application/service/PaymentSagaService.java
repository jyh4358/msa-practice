package com.shopsaga.payment.application.service;

import com.shopsaga.events.InventoryReservedEvent;
import com.shopsaga.events.PaymentChargedEvent;
import com.shopsaga.events.PaymentDeclinedEvent;
import com.shopsaga.payment.application.UseCase;
import com.shopsaga.payment.application.port.in.PaymentSagaUseCase;
import com.shopsaga.payment.application.port.out.ProcessedMessagePort;
import com.shopsaga.payment.application.port.out.PublishPaymentEventPort;
import com.shopsaga.payment.application.port.out.SavePaymentPort;
import com.shopsaga.payment.domain.Payment;
import com.shopsaga.payment.domain.PaymentDeclinedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 12: 결제의 Saga 참여 — 재고 예약 성공을 듣고 청구한다.
 *
 * <p>성공/거절 <b>모두 한 트랜잭션</b>으로 처리할 수 있다: 거절은 도메인이 DB에 아무것도 쓰기 전에
 * 판단하므로 롤백할 부수효과가 없다(재고 예약과 달리 부분 상태가 생기지 않는다).
 * 그래서 결제 저장·이벤트 기록·처리기록이 한 커밋으로 원자적이다.
 */
@UseCase
@RequiredArgsConstructor
@Slf4j
class PaymentSagaService implements PaymentSagaUseCase {

    private final SavePaymentPort savePaymentPort;
    private final PublishPaymentEventPort publishPaymentEventPort;
    private final ProcessedMessagePort processedMessagePort;

    @Override
    @Transactional
    public void onInventoryReserved(UUID messageId, InventoryReservedEvent event) {
        if (processedMessagePort.isAlreadyProcessed(messageId)) {
            // 결제에서 중복은 곧 이중 청구 — 이 가드가 가장 중요한 방어선이다.
            log.info("이미 처리된 메시지 — 결제 건너뜀 messageId={} orderId={}", messageId, event.orderId());
            return;
        }

        try {
            Payment captured = savePaymentPort.save(Payment.capture(event.orderId(), event.totalAmount()));
            publishPaymentEventPort.paymentCharged(new PaymentChargedEvent(
                    event.orderId(), captured.getId(), captured.getAmount(), Instant.now()));
            log.info("결제 성공 orderId={} paymentId={} amount={}",
                    event.orderId(), captured.getId(), captured.getAmount());

        } catch (PaymentDeclinedException e) {
            // 거절은 예외가 아니라 업무 결과 — 사실로 발행하고 정상 종료한다(재시도 루프 방지).
            // 이 이벤트를 inventory(보상)와 order(취소)가 각자 듣는다.
            publishPaymentEventPort.paymentDeclined(new PaymentDeclinedEvent(
                    event.orderId(), event.totalAmount(), e.getMessage(), Instant.now()));
            log.warn("결제 거절 → PaymentDeclined 발행 orderId={} amount={} reason={}",
                    event.orderId(), event.totalAmount(), e.getMessage());
        }

        processedMessagePort.markProcessed(messageId);
    }
}
