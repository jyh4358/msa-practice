package com.shopsaga.order.application.service;

import com.shopsaga.events.InventoryFailedEvent;
import com.shopsaga.events.InventoryReservedEvent;
import com.shopsaga.events.OrderCancelledEvent;
import com.shopsaga.events.OrderConfirmedEvent;
import com.shopsaga.events.PaymentChargedEvent;
import com.shopsaga.events.PaymentDeclinedEvent;
import com.shopsaga.order.application.UseCase;
import com.shopsaga.order.application.port.in.OrderSagaUseCase;
import com.shopsaga.order.application.port.out.LoadOrderPort;
import com.shopsaga.order.application.port.out.ProcessedMessagePort;
import com.shopsaga.order.application.port.out.PublishOrderEventPort;
import com.shopsaga.order.application.port.out.UpdateOrderPort;
import com.shopsaga.order.domain.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Phase 12: 주문의 Saga 반응기. 다른 서비스가 발행한 사실을 듣고 주문 상태만 전이시킨다.
 *
 * <p>모든 핸들러가 같은 골격을 따른다:
 * <ol>
 *   <li><b>멱등 가드</b> — 이미 처리한 messageId 면 즉시 종료(재배달 흡수).</li>
 *   <li>주문 로드 → <b>도메인이 전이 가능한지 판단</b>(전이 규칙은 도메인의 몫).</li>
 *   <li>전이됐으면 상태 저장 + <b>결과 이벤트를 outbox 에</b> 기록(같은 트랜잭션 = 원자적).</li>
 *   <li>처리 완료 기록.</li>
 * </ol>
 * 2~4가 <b>한 트랜잭션</b>이므로 "상태는 바뀌었는데 이벤트가 안 나감" 같은 부분 실패가 없다.
 */
@UseCase
@RequiredArgsConstructor
@Slf4j
class OrderSagaService implements OrderSagaUseCase {

    private final LoadOrderPort loadOrderPort;
    private final UpdateOrderPort updateOrderPort;
    private final PublishOrderEventPort publishOrderEventPort;
    private final ProcessedMessagePort processedMessagePort;

    @Override
    @Transactional
    public void onInventoryReserved(UUID messageId, InventoryReservedEvent event) {
        handle(messageId, event.orderId(), "InventoryReserved",
                Order::markInventoryReserved,
                order -> {
                    // 상태만 전이한다. 다음 단계(결제)는 payment-service가 같은 InventoryReserved 를 듣고 수행 —
                    // order가 "결제해라"라고 명령하지 않는 것이 코레오그래피의 핵심.
                });
    }

    @Override
    @Transactional
    public void onInventoryFailed(UUID messageId, InventoryFailedEvent event) {
        handle(messageId, event.orderId(), "InventoryFailed",
                Order::cancel,
                order -> publishOrderEventPort.orderCancelled(new OrderCancelledEvent(
                        order.getId(), "재고 예약 실패: " + event.reason(), Instant.now())));
    }

    @Override
    @Transactional
    public void onPaymentCharged(UUID messageId, PaymentChargedEvent event) {
        handle(messageId, event.orderId(), "PaymentCharged",
                order -> order.confirm(event.paymentId()),
                order -> publishOrderEventPort.orderConfirmed(new OrderConfirmedEvent(
                        order.getId(), order.getPaymentId(), Instant.now())));
    }

    @Override
    @Transactional
    public void onPaymentDeclined(UUID messageId, PaymentDeclinedEvent event) {
        handle(messageId, event.orderId(), "PaymentDeclined",
                Order::cancel,
                order -> publishOrderEventPort.orderCancelled(new OrderCancelledEvent(
                        order.getId(), "결제 거절: " + event.reason(), Instant.now())));
    }

    /**
     * 공통 골격: 멱등 가드 → 로드 → 도메인 전이 시도 → (전이됐으면) 저장 + 결과 이벤트 발행 → 처리 기록.
     *
     * @param transition 도메인 전이 시도. 실제로 전이했으면 true(멱등하게 false 를 반환할 수 있다)
     * @param onTransitioned 전이 성공 시 부수효과(결과 이벤트 발행)
     */
    private void handle(UUID messageId, UUID orderId, String eventName,
                        Predicate<Order> transition, java.util.function.Consumer<Order> onTransitioned) {
        if (processedMessagePort.isAlreadyProcessed(messageId)) {
            log.info("이미 처리된 메시지 — 건너뜀 messageId={} event={} orderId={}", messageId, eventName, orderId);
            return;
        }

        Optional<Order> found = loadOrderPort.loadById(orderId);
        if (found.isEmpty()) {
            // 주문이 없다 = 이 서비스가 모르는 주문(데이터 불일치). 재시도해도 소용없으므로 처리됨으로 표시하고 넘어간다.
            log.warn("알 수 없는 주문의 이벤트 — 무시 messageId={} event={} orderId={}", messageId, eventName, orderId);
            processedMessagePort.markProcessed(messageId);
            return;
        }

        Order order = found.get();
        if (transition.test(order)) {
            updateOrderPort.update(order);
            onTransitioned.accept(order);
            log.info("Saga 전이 event={} orderId={} → status={}", eventName, orderId, order.getStatus());
        } else {
            // 도메인이 전이를 거부 = 이미 그 상태이거나 종료 상태(취소/확정). 정상적인 멱등 무시.
            log.info("전이 조건 불충족 — 무시 event={} orderId={} status={}", eventName, orderId, order.getStatus());
        }
        processedMessagePort.markProcessed(messageId);
    }
}
