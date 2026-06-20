package com.shopsaga.order.application.port.in;

import com.shopsaga.order.domain.StockItem;

import java.util.UUID;

/** 인바운드 포트(쿼리): 재고 조회. */
public interface GetStockQuery {

    StockItem getStock(UUID productId);
}
