package com.shopsaga.orderquery.application.service;

import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.orderquery.application.UseCase;
import com.shopsaga.orderquery.application.port.in.ProjectOrderPlacedUseCase;
import com.shopsaga.orderquery.application.port.out.OrderViewRepositoryPort;
import com.shopsaga.orderquery.domain.OrderView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Phase 11: 투영기(projector) — {@code OrderPlaced} 이벤트를 읽기 모델로 변환해 저장한다.
 *
 * <p><b>결정성(determinism)이 이 클래스의 핵심 규칙</b>이다:
 * <ul>
 *   <li>시각·총액 등 모든 값은 <b>이벤트에서만</b> 가져온다. {@code Instant.now()}·{@code UUID.randomUUID()}
 *       같은 걸 쓰면 리플레이마다 결과가 달라져 "읽기 DB 삭제 → offset 0부터 재생 → 동일 상태" 검증이 거짓이 된다.</li>
 *   <li>저장은 orderId 기준 <b>덮어쓰기(upsert)</b> → 같은 이벤트를 여러 번 받아도 결과가 같다(멱등).</li>
 * </ul>
 * 그래서 이 서비스에는 별도 dedup 테이블이 없다 — 투영 자체가 멱등하기 때문이다
 * (부수효과가 있는 재고 예약은 Phase 10처럼 {@code processed_messages} 가 필요했지만, 투영은 순수 덮어쓰기).
 */
@UseCase
@RequiredArgsConstructor
@Slf4j
class OrderViewProjectionService implements ProjectOrderPlacedUseCase {

    /** 이 투영이 만들어내는 상태. Phase 12에서 InventoryReserved/PaymentCharged 로 전이가 생긴다. */
    private static final String STATUS_CONFIRMED = "CONFIRMED";

    private final OrderViewRepositoryPort repository;

    @Override
    public void project(OrderPlacedEvent event) {
        List<OrderView.Line> lines = event.items().stream()
                .map(i -> new OrderView.Line(i.productId(), i.quantity(), i.unitPrice()))
                .toList();

        OrderView view = new OrderView(
                event.orderId(),
                event.customerId(),
                STATUS_CONFIRMED,
                event.totalAmount(),   // 이벤트가 실어 온 값(재계산하지 않음)
                event.occurredAt(),    // ★ 이벤트의 시각 — 투영 시점의 시계를 읽지 않는다
                lines);

        repository.save(view);   // orderId 기준 upsert → 리플레이/중복에 멱등
        log.info("읽기 모델 투영 orderId={} customer={} 품목수={} total={}",
                event.orderId(), event.customerId(), lines.size(), event.totalAmount());
    }
}
