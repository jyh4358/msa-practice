package com.shopsaga.orderquery.application.service;

import com.shopsaga.events.InventoryReservedEvent;
import com.shopsaga.events.OrderCancelledEvent;
import com.shopsaga.events.OrderConfirmedEvent;
import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.orderquery.application.UseCase;
import com.shopsaga.orderquery.application.port.in.ProjectOrderEventsUseCase;
import com.shopsaga.orderquery.application.port.out.OrderViewRepositoryPort;
import com.shopsaga.orderquery.domain.OrderView;
import com.shopsaga.orderquery.domain.OrderViewStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

/**
 * Phase 11/12: 투영기(projector) — Saga 이벤트를 읽기 모델로 변환해 저장한다.
 *
 * <p><b>결정성(determinism)이 이 클래스의 핵심 규칙</b>이다:
 * <ul>
 *   <li>시각·총액 등 모든 값은 <b>이벤트에서만</b> 가져온다. {@code Instant.now()} 같은 걸 쓰면
 *       리플레이마다 결과가 달라져 "읽기 DB 삭제 → offset 0부터 재생 → 동일 상태" 검증이 거짓이 된다.</li>
 *   <li>본문 저장은 orderId 기준 <b>덮어쓰기</b>(단, status 제외) → 재배달·리플레이에 멱등.</li>
 *   <li>상태는 <b>단조롭게</b>만 전진(Phase 12) → 토픽 간 도착 순서가 뒤바뀌어도 최종 상태가 같다.</li>
 * </ul>
 * 그래서 이 서비스에는 dedup 테이블이 없다 — 투영 자체가 멱등하기 때문이다.
 */
@UseCase
@RequiredArgsConstructor
@Slf4j
class OrderViewProjectionService implements ProjectOrderEventsUseCase {

    private final OrderViewRepositoryPort repository;

    @Override
    public void onOrderPlaced(OrderPlacedEvent event) {
        List<OrderView.Line> lines = event.items().stream()
                .map(i -> new OrderView.Line(i.productId(), i.quantity(), i.unitPrice()))
                .toList();

        OrderView view = new OrderView(
                event.orderId(),
                event.customerId(),
                OrderViewStatus.PENDING.name(),
                event.totalAmount(),   // 이벤트가 실어 온 값(재계산하지 않음)
                event.occurredAt(),    // ★ 이벤트의 시각 — 투영 시점의 시계를 읽지 않는다
                lines);

        // 본문만 upsert. 이미 진행된 주문의 상태를 되돌리지 않기 위해 status 는 신규 생성 시에만 넣는다.
        repository.upsertBase(view, OrderViewStatus.PENDING.name());
        log.info("읽기 모델 투영(생성) orderId={} customer={} 품목수={} total={}",
                event.orderId(), event.customerId(), lines.size(), event.totalAmount());
    }

    @Override
    public void onInventoryReserved(InventoryReservedEvent event) {
        transitionTo(event.orderId(), OrderViewStatus.INVENTORY_RESERVED);
    }

    @Override
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        transitionTo(event.orderId(), OrderViewStatus.CONFIRMED);
    }

    @Override
    public void onOrderCancelled(OrderCancelledEvent event) {
        transitionTo(event.orderId(), OrderViewStatus.CANCELLED);
    }

    private void transitionTo(UUID orderId, OrderViewStatus target) {
        boolean applied = repository.applyStatusIfCurrentIn(
                orderId, target.name(), target.overwritableStatuses());
        if (applied) {
            log.info("읽기 모델 상태 전이 orderId={} → {}", orderId, target);
        } else {
            // 문서가 아직 없거나(OrderPlaced 미도착) 이미 더 진행된 상태 — 둘 다 정상(단조 전이).
            log.info("상태 전이 건너뜀(문서 없음 또는 이미 진행됨) orderId={} target={}", orderId, target);
        }
    }
}
