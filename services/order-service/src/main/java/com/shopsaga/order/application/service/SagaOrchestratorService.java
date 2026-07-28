package com.shopsaga.order.application.service;

import com.shopsaga.events.OrderCancelledEvent;
import com.shopsaga.events.OrderConfirmedEvent;
import com.shopsaga.events.commands.ChargePaymentCommand;
import com.shopsaga.events.commands.RefundPaymentCommand;
import com.shopsaga.events.commands.ReleaseStockCommand;
import com.shopsaga.events.commands.SagaReply;
import com.shopsaga.order.application.UseCase;
import com.shopsaga.order.application.port.in.SagaReplyUseCase;
import com.shopsaga.order.application.port.out.LoadOrderPort;
import com.shopsaga.order.application.port.out.ProcessedMessagePort;
import com.shopsaga.order.application.port.out.PublishOrderEventPort;
import com.shopsaga.order.application.port.out.PublishSagaCommandPort;
import com.shopsaga.order.application.port.out.SagaInstanceRepositoryPort;
import com.shopsaga.order.application.port.out.UpdateOrderPort;
import com.shopsaga.order.domain.Order;
import com.shopsaga.order.domain.saga.SagaInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 13: <b>Saga 오케스트레이터</b> — 손으로 만든 중앙 조정자.
 *
 * <p><b>Phase 12(코레오그래피)와의 차이가 이 클래스에 전부 들어 있다.</b>
 * 거기서는 "재고가 예약됐다"는 사실을 payment가 <b>스스로 듣고</b> 결제를 시작했다.
 * 여기서는 참여 서비스가 결과만 돌려주고, <b>다음에 무엇을 할지는 이 스위치문이 결정</b>한다.
 * 덕분에 Saga 전체 흐름을 이 파일 하나로 읽을 수 있다(코레오그래피의 최대 단점 해결).
 *
 * <p>대가도 분명하다: 참여 서비스들이 조정자에 <b>결합</b>되고(커맨드를 기다림), 조정자가 <b>단일 고장점</b>이 된다.
 *
 * <p>모든 핸들러가 한 트랜잭션에서 ① 멱등 가드 → ② Saga 상태 전이 → ③ 주문 상태 전이 →
 * ④ 다음 커맨드/결과 이벤트를 outbox 에 기록 → ⑤ 처리 기록을 수행한다. 그래서 "상태는 바뀌었는데
 * 다음 커맨드가 안 나감" 같은 부분 실패가 없다.
 */
@UseCase
@RequiredArgsConstructor
@Slf4j
class SagaOrchestratorService implements SagaReplyUseCase {

    private final SagaInstanceRepositoryPort sagaRepository;
    private final LoadOrderPort loadOrderPort;
    private final UpdateOrderPort updateOrderPort;
    private final PublishSagaCommandPort publishSagaCommandPort;
    private final PublishOrderEventPort publishOrderEventPort;
    private final ProcessedMessagePort processedMessagePort;

    @Override
    @Transactional
    public void onReply(UUID messageId, SagaReply reply) {
        if (processedMessagePort.isAlreadyProcessed(messageId)) {
            log.info("이미 처리된 리플라이 — 건너뜀 messageId={} kind={} sagaId={}",
                    messageId, reply.kind(), reply.sagaId());
            return;
        }

        Optional<SagaInstance> found = sagaRepository.findBySagaId(reply.sagaId());
        if (found.isEmpty()) {
            // 이 조정자가 모르는 Saga — 재시도해도 소용없으므로 처리됨으로 표시하고 넘어간다.
            log.warn("알 수 없는 Saga의 리플라이 — 무시 sagaId={} kind={}", reply.sagaId(), reply.kind());
            processedMessagePort.markProcessed(messageId);
            return;
        }

        SagaInstance saga = found.get();
        Instant now = Instant.now();

        // ★ Saga 전체의 분기점 — 여기만 읽으면 흐름을 알 수 있다.
        switch (reply.kind()) {
            case STOCK_RESERVED -> onStockReserved(saga, reply, now);
            case STOCK_RESERVATION_FAILED -> onStockReservationFailed(saga, reply, now);
            case PAYMENT_CHARGED -> onPaymentCharged(saga, reply, now);
            case PAYMENT_DECLINED -> onPaymentDeclined(saga, reply, now);
            case STOCK_RELEASED -> onStockReleased(saga, reply, now);
            case PAYMENT_REFUNDED -> log.warn("고아 결제 정리 완료 sagaId={} orderId={} paymentId={}",
                    saga.getSagaId(), saga.getOrderId(), reply.paymentId());
        }

        processedMessagePort.markProcessed(messageId);
    }

    /** 재고 확보 성공 → 다음 단계(결제)를 지시한다. */
    private void onStockReserved(SagaInstance saga, SagaReply reply, Instant now) {
        if (!saga.awaitPayment(now)) {
            log.info("전이 조건 불충족 — 무시 kind=STOCK_RESERVED sagaId={} state={}", saga.getSagaId(), saga.getState());
            return;
        }
        sagaRepository.update(saga);
        transitionOrder(saga.getOrderId(), Order::markInventoryReserved);

        Order order = loadOrderPort.loadById(saga.getOrderId()).orElseThrow();
        publishSagaCommandPort.chargePayment(new ChargePaymentCommand(
                saga.getSagaId(), saga.getOrderId(), order.getTotalAmount(), now));
        log.info("Saga 진행 sagaId={} orderId={} → AWAITING_PAYMENT (ChargePayment 지시)",
                saga.getSagaId(), saga.getOrderId());
    }

    /** 재고 확보 실패 → 되돌릴 것이 없다(짧은 보상). 바로 주문 취소로 종료. */
    private void onStockReservationFailed(SagaInstance saga, SagaReply reply, Instant now) {
        if (!saga.cancel(now)) {
            return;
        }
        sagaRepository.update(saga);
        cancelOrder(saga.getOrderId(), "재고 예약 실패: " + reply.reason(), now);
        log.info("Saga 종료 sagaId={} orderId={} → CANCELLED (재고 실패, 보상 불필요)",
                saga.getSagaId(), saga.getOrderId());
    }

    /** 결제 성공 → 주문 확정으로 성공 종료. 단, <b>이미 끝난 Saga의 뒤늦은 성공</b>이면 보상해야 한다. */
    private void onPaymentCharged(SagaInstance saga, SagaReply reply, Instant now) {
        if (!saga.complete(now)) {
            // ★ Phase 14: Phase 13이 남긴 '고아 결제' 결함을 여기서 막는다.
            //    타임아웃으로 포기한 Saga는 이미 CANCELLED 인데, 되살아난 payment 가 큐에 남아 있던
            //    ChargePayment 를 뒤늦게 수행하면 "취소된 주문에 결제만 살아 있는" 상태가 된다.
            //    무시하면 돈이 빠져나간 채로 남으므로, 되돌릴 수 없는 대신 <b>상쇄</b>를 지시한다.
            compensateOrphanPayment(saga, reply, now);
            return;
        }
        sagaRepository.update(saga);
        transitionOrder(saga.getOrderId(), order -> order.confirm(reply.paymentId()));

        publishOrderEventPort.orderConfirmed(new OrderConfirmedEvent(
                saga.getOrderId(), reply.paymentId(), now));
        log.info("Saga 종료 sagaId={} orderId={} → COMPLETED (주문 확정)", saga.getSagaId(), saga.getOrderId());
    }

    /** 결제 거절 → 잡아둔 재고를 풀라고 <b>지시</b>한다(긴 보상의 시작). */
    private void onPaymentDeclined(SagaInstance saga, SagaReply reply, Instant now) {
        if (!saga.startCompensation(now)) {
            log.info("전이 조건 불충족 — 무시 kind=PAYMENT_DECLINED sagaId={} state={}", saga.getSagaId(), saga.getState());
            return;
        }
        sagaRepository.update(saga);
        // ⚠️ 여기서 주문을 바로 취소하지 않는다 — 보상이 끝난 뒤에 종료한다.
        //    코레오그래피에서는 order와 inventory가 같은 이벤트를 듣고 '동시에' 반응했지만,
        //    조정자는 보상 완료를 확인하고 나서 종료해 순서를 통제한다.
        publishSagaCommandPort.releaseStock(new ReleaseStockCommand(saga.getSagaId(), saga.getOrderId(), now));
        log.info("Saga 보상 시작 sagaId={} orderId={} → COMPENSATING_INVENTORY (ReleaseStock 지시)",
                saga.getSagaId(), saga.getOrderId());
    }

    /** 재고 해제 완료 → 보상이 끝났으니 주문을 취소하고 종료. */
    private void onStockReleased(SagaInstance saga, SagaReply reply, Instant now) {
        if (!saga.cancel(now)) {
            return;
        }
        sagaRepository.update(saga);
        cancelOrder(saga.getOrderId(), "결제 거절 → 재고 해제 완료", now);
        log.info("Saga 종료 sagaId={} orderId={} → CANCELLED (보상 완료)", saga.getSagaId(), saga.getOrderId());
    }

    /**
     * Phase 14: 종료된 Saga 뒤에 도착한 결제 성공을 되돌리라고 지시한다.
     *
     * <p>Saga 상태는 건드리지 않는다 — 이미 CANCELLED 로 끝난 사건이고, 환불은 그 <b>뒤에 붙는 정리 작업</b>이다.
     * (상태를 되돌리면 "취소됐다가 다시 진행 중"이 되어 sweep 대상으로 되살아난다.)
     * 결제 id 가 없으면 되돌릴 대상을 특정할 수 없으므로 로그만 남긴다(설계상 성공 리플라이엔 항상 있다).
     */
    private void compensateOrphanPayment(SagaInstance saga, SagaReply reply, Instant now) {
        if (reply.paymentId() == null) {
            log.error("Saga 종료 후 결제 성공이 왔는데 paymentId 가 없어 보상 불가 sagaId={} state={}",
                    saga.getSagaId(), saga.getState());
            return;
        }
        log.error("★ 고아 결제 감지 — 환불 지시 sagaId={} orderId={} paymentId={} sagaState={}",
                saga.getSagaId(), saga.getOrderId(), reply.paymentId(), saga.getState());
        publishSagaCommandPort.refundPayment(new RefundPaymentCommand(
                saga.getSagaId(), saga.getOrderId(), reply.paymentId(),
                "Saga 종료(" + saga.getState() + ") 후 도착한 결제", now));
    }

    private void cancelOrder(UUID orderId, String reason, Instant now) {
        if (transitionOrder(orderId, Order::cancel)) {
            publishOrderEventPort.orderCancelled(new OrderCancelledEvent(orderId, reason, now));
        }
    }

    /** 주문 애그리거트 전이(성공 시 true). 주문이 없거나 도메인이 거부하면 false. */
    private boolean transitionOrder(UUID orderId, java.util.function.Predicate<Order> transition) {
        Optional<Order> found = loadOrderPort.loadById(orderId);
        if (found.isEmpty()) {
            log.warn("주문을 찾을 수 없음 orderId={}", orderId);
            return false;
        }
        Order order = found.get();
        if (!transition.test(order)) {
            return false;
        }
        updateOrderPort.update(order);
        return true;
    }
}
