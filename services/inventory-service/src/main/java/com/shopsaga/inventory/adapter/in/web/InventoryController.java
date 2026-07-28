package com.shopsaga.inventory.adapter.in.web;

import com.shopsaga.inventory.application.port.in.GetStockQuery;
import com.shopsaga.inventory.application.port.in.StockView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 재고 조회용 인바운드 웹 어댑터(주문 전후 재고 비교). 출력은 StockView(불변 뷰). */
@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
@Tag(name = "Inventory", description = "재고 조회 (주문 전후 비교용)")
class InventoryController {

    private final GetStockQuery getStockQuery;
    /** Phase 14: 장애 주입 스위치(꺼져 있으면 빈 자체가 없다). */
    private final ObjectProvider<ChaosSwitch> chaosSwitch;

    @GetMapping("/{productId}")
    @Operation(summary = "상품 재고 조회",
            description = "상품의 현재 가용 수량. 미등록 상품 → 404. "
                    + "Phase 14: chaos 가 켜져 있으면 설정된 확률/지연이 여기에 주입된다.")
    StockView get(@PathVariable UUID productId) {
        // ⚠️ 이 조회는 order-service 의 '사전 확인'이 때리는 지점이다 — 복원력 패턴의 관찰 대상.
        chaosSwitch.ifAvailable(ChaosSwitch::maybeDisrupt);
        return getStockQuery.getStock(productId);
    }
}
