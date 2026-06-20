package com.shopsaga.order.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockItemTest {

    @Test
    void reserve_decrementsAvailableQuantity() {
        StockItem stock = new StockItem(UUID.randomUUID(), 10);

        stock.reserve(3);

        assertThat(stock.getAvailableQuantity()).isEqualTo(7);
    }

    @Test
    void reserve_throwsWhenInsufficient() {
        StockItem stock = new StockItem(UUID.randomUUID(), 2);

        assertThatThrownBy(() -> stock.reserve(5))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(stock.getAvailableQuantity()).isEqualTo(2); // 변경 없음
    }
}
