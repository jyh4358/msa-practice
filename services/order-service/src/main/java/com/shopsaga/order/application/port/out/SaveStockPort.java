package com.shopsaga.order.application.port.out;

import com.shopsaga.order.domain.StockItem;

/** 아웃바운드 포트: 재고 저장(차감 결과 반영). */
public interface SaveStockPort {

    void save(StockItem stockItem);
}
