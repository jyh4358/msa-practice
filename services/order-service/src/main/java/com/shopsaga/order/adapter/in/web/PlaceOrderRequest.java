package com.shopsaga.order.adapter.in.web;

import com.shopsaga.order.application.port.in.PlaceOrderCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 웹 어댑터의 입력 DTO(검증 포함). 인바운드 포트 커맨드로 변환된다. */
public record PlaceOrderRequest(
        @NotNull UUID customerId,
        @NotEmpty @Valid List<Item> items
) {
    public record Item(
            @NotNull UUID productId,
            @Positive int quantity,
            @NotNull @Positive BigDecimal unitPrice
    ) {
    }

    public PlaceOrderCommand toCommand() {
        List<PlaceOrderCommand.Item> commandItems = items.stream()
                .map(i -> new PlaceOrderCommand.Item(i.productId(), i.quantity(), i.unitPrice()))
                .toList();
        return new PlaceOrderCommand(customerId, commandItems);
    }
}
