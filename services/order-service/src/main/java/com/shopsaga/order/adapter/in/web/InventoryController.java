package com.shopsaga.order.adapter.in.web;

import com.shopsaga.order.application.port.in.GetStockQuery;
import com.shopsaga.order.domain.StockItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 재고 조회용 인바운드 웹 어댑터(데모: 주문 전후 재고 비교). */
@RestController
@RequestMapping("/inventory")
@Tag(name = "Inventory", description = "재고 조회 (주문 전후 비교용)")
class InventoryController {

    private final GetStockQuery getStockQuery;

    InventoryController(GetStockQuery getStockQuery) {
        this.getStockQuery = getStockQuery;
    }

    @GetMapping("/{productId}")
    @Operation(summary = "상품 재고 조회", description = "상품의 현재 가용 수량. 미등록 상품 → 404.")
    StockView get(@PathVariable UUID productId) {
        StockItem stock = getStockQuery.getStock(productId);
        return new StockView(stock.getProductId(), stock.getAvailableQuantity());
    }

    record StockView(UUID productId, int availableQuantity) {
    }
}
