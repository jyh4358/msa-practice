package com.shopsaga.order.application.port.out;

import com.shopsaga.order.domain.StockItem;

import java.util.Optional;
import java.util.UUID;

/** 아웃바운드 포트: 재고 조회. */
public interface LoadStockPort {

    Optional<StockItem> loadByProductId(UUID productId);
}
