package com.shopsaga.orderquery.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Phase 11: 읽기 모델(read model) — <b>순수 도메인</b>, 저장 기술 무관.
 *
 * <p>쓰기 모델(order-service의 {@code Order})과 <b>다른 모양</b>이다: 조회 화면이 필요한 형태로
 * <b>비정규화</b>(denormalized)되어 있다 — 조인 없이 한 번의 조회로 끝나게 품목을 문서 안에 품는다.
 *
 * <p>이 객체는 이벤트로부터 <b>투영(projection)</b>된다. 그래서 필드 값은 모두 이벤트가 실어 온 사실이며,
 * 투영 과정에서 {@code Instant.now()} 같은 <b>비결정적 값을 만들지 않는다</b> —
 * 그래야 같은 이벤트 스트림을 리플레이하면 <b>항상 같은 상태</b>가 된다(투영의 결정성).
 */
@Getter
public class OrderView {

    private final UUID orderId;
    private final UUID customerId;
    private final String status;
    private final BigDecimal totalAmount;
    private final Instant placedAt;      // = OrderPlaced.occurredAt (이벤트가 실어 온 시각)
    private final List<Line> lines;

    public OrderView(UUID orderId, UUID customerId, String status, BigDecimal totalAmount,
                     Instant placedAt, List<Line> lines) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.placedAt = placedAt;
        this.lines = List.copyOf(lines);
    }

    public List<Line> getLines() {
        return Collections.unmodifiableList(lines);
    }

    /** 주문 한 줄(품목) — 비정규화되어 읽기 문서 안에 함께 저장된다. */
    @Getter
    public static class Line {
        private final UUID productId;
        private final int quantity;
        private final BigDecimal unitPrice;
        private final BigDecimal lineTotal;   // 조회 편의를 위해 미리 계산해 저장(읽기 최적화)

        public Line(UUID productId, int quantity, BigDecimal unitPrice) {
            this.productId = productId;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }
}
