package com.shopsaga.inventory.adapter.out.persistence;

import com.shopsaga.inventory.application.port.out.StockReservationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 아웃바운드 어댑터: 예약 원장(stock_reservations) 기록/회수. */
@Component
@RequiredArgsConstructor
class StockReservationPersistenceAdapter implements StockReservationPort {

    private final StockReservationJpaRepository repository;

    @Override
    public void record(UUID orderId, Map<UUID, Integer> quantityByProduct) {
        quantityByProduct.forEach((productId, quantity) ->
                repository.save(new StockReservationJpaEntity(orderId, productId, quantity)));
    }

    @Override
    public Map<UUID, Integer> takeForRelease(UUID orderId) {
        List<StockReservationJpaEntity> rows = repository.findByOrderId(orderId);
        Map<UUID, Integer> reserved = new HashMap<>();
        rows.forEach(r -> reserved.merge(r.getProductId(), r.getQuantity(), Integer::sum));
        if (!rows.isEmpty()) {
            // 꺼냈으면 지운다 → 같은 주문을 두 번 보상해도 두 번째는 빈 결과(자연 멱등).
            repository.deleteByOrderId(orderId);
        }
        return reserved;
    }
}
