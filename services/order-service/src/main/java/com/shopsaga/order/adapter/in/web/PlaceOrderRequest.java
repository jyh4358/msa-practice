package com.shopsaga.order.adapter.in.web;

import com.shopsaga.order.application.port.in.PlaceOrderCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 웹 어댑터의 입력 DTO(검증 포함). 인바운드 포트 커맨드로 변환된다.
 *
 * <p>★ 감사(2026-08-02) 수정: {@code customerId} 필드 제거. 클라이언트가 몸통으로 보내는 고객 id를
 * 믿으면 아무나 남의 이름으로 주문을 만들 수 있다 — 고객 id는 <b>JWT subject 에서 유도</b>한다
 * ({@link CustomerIds}). 예전 스크립트가 customerId 를 계속 보내도 Jackson 이 무시하므로 호환된다.
 */
public record PlaceOrderRequest(
        @NotEmpty @Valid List<Item> items
) {
    public record Item(
            @NotNull UUID productId,
            @Positive int quantity,
            @NotNull @Positive BigDecimal unitPrice
    ) {
    }

    public PlaceOrderCommand toCommand(UUID customerId) {
        List<PlaceOrderCommand.Item> commandItems = items.stream()
                .map(i -> new PlaceOrderCommand.Item(i.productId(), i.quantity(), i.unitPrice()))
                .toList();
        return new PlaceOrderCommand(customerId, commandItems);
    }
}
