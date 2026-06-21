package com.shopsaga.order.application.port.out;

import com.shopsaga.order.domain.StockItem;

import java.util.Optional;
import java.util.UUID;

/** 아웃바운드 포트: 재고 조회(읽기 전용). 차감(락)은 ReserveStockPort 가 담당. */
public interface LoadStockPort {

    Optional<StockItem> loadByProductId(UUID productId);
}
