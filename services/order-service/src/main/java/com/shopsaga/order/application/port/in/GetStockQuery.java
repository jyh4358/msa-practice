package com.shopsaga.order.application.port.in;

import java.util.UUID;

/** 인바운드 포트(쿼리): 재고를 조회한다. 출력은 StockView(불변 뷰). */
public interface GetStockQuery {

    StockView getStock(UUID productId);
}
