package com.shopsaga.order.application.port.in;

import com.shopsaga.order.domain.StockItem;

import java.util.UUID;

/** 인바운드 포트의 출력 모델(불변 read model) — 재고. */
public record StockView(UUID productId, int availableQuantity) {

    public static StockView from(StockItem stockItem) {
        return new StockView(stockItem.getProductId(), stockItem.getAvailableQuantity());
    }
}
