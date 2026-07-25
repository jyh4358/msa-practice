package com.shopsaga.orderquery.application.port.in;

import com.shopsaga.orderquery.domain.OrderView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 인바운드 포트의 <b>불변 출력 모델</b> — 도메인 애그리거트를 웹 어댑터에 노출하지 않기 위한 경계
 * (다른 서비스의 {@code *View} 와 같은 역할. 여기선 도메인 이름이 이미 OrderView 라 Summary 로 명명).
 */
public record OrderSummary(
        UUID orderId,
        UUID customerId,
        String status,
        BigDecimal totalAmount,
        Instant placedAt,
        List<Line> lines
) {
    public record Line(UUID productId, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}

    public static OrderSummary from(OrderView view) {
        List<Line> lines = view.getLines().stream()
                .map(l -> new Line(l.getProductId(), l.getQuantity(), l.getUnitPrice(), l.getLineTotal()))
                .toList();
        return new OrderSummary(view.getOrderId(), view.getCustomerId(), view.getStatus(),
                view.getTotalAmount(), view.getPlacedAt(), lines);
    }
}
