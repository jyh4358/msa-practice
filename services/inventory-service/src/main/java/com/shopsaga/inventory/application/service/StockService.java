package com.shopsaga.inventory.application.service;

import com.shopsaga.inventory.application.UseCase;
import com.shopsaga.inventory.application.port.in.GetStockQuery;
import com.shopsaga.inventory.application.port.in.ReserveStockUseCase;
import com.shopsaga.inventory.application.port.in.StockView;
import com.shopsaga.inventory.application.port.out.LoadStockPort;
import com.shopsaga.inventory.application.port.out.ReserveStockPort;
import com.shopsaga.inventory.domain.InsufficientStockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 재고 유스케이스 구현. 조회(GetStockQuery) + 예약(ReserveStockUseCase, Kafka 소비자가 호출).
 */
@UseCase
@RequiredArgsConstructor
@Slf4j
class StockService implements GetStockQuery, ReserveStockUseCase {

    private final LoadStockPort loadStockPort;
    private final ReserveStockPort reserveStockPort;

    @Override
    @Transactional(readOnly = true)
    public StockView getStock(UUID productId) {
        return loadStockPort.loadByProductId(productId)
                .map(StockView::from)
                .orElseThrow(() -> new StockNotFoundException(productId));
    }

    @Override
    @Transactional
    public void reserveForOrder(UUID orderId, Map<UUID, Integer> quantityByProduct) {
        // 상품ID 정렬(TreeMap)로 교착 회피 — 여러 주문이 같은 상품들을 동시에 예약해도 락 획득 순서를 일관되게.
        new TreeMap<>(quantityByProduct).forEach((productId, qty) -> {
            try {
                reserveStockPort.reserve(productId, qty);
                log.info("재고 예약 성공 orderId={} product={} qty={}", orderId, productId, qty);
            } catch (InsufficientStockException | StockNotFoundException e) {
                // Phase 9a: 보상 없음 — 로그만 남긴다. 재고가 부족해도 주문은 이미 CONFIRMED(결과적 일관성 문제).
                //           이 잃어버린 정합성이 Phase 12 Saga(보상=재고 해제/주문 취소)의 동기다.
                log.warn("재고 예약 실패 orderId={} product={} qty={} — {}", orderId, productId, qty, e.getMessage());
            }
        });
    }
}
