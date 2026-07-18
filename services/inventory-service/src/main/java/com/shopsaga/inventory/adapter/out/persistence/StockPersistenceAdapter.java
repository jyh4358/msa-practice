package com.shopsaga.inventory.adapter.out.persistence;

import com.shopsaga.inventory.application.port.out.LoadStockPort;
import com.shopsaga.inventory.application.port.out.ReserveStockPort;
import com.shopsaga.inventory.application.service.StockNotFoundException;
import com.shopsaga.inventory.domain.StockItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** 아웃바운드 영속 어댑터: 재고 조회(LoadStockPort) + 예약(ReserveStockPort). 예약은 비관적 락으로 차감. */
@Component
@RequiredArgsConstructor
class StockPersistenceAdapter implements LoadStockPort, ReserveStockPort {

    private final StockItemJpaRepository repository;
    private final StockQueryRepository queryRepository;

    @Override
    public Optional<StockItem> loadByProductId(UUID productId) {
        // 읽기 전용(재고 조회 엔드포인트) — 락 없음.
        return repository.findById(productId).map(this::toDomain);
    }

    @Override
    public void reserve(UUID productId, int quantity) {
        // 비관적 쓰기 락으로 행을 잠근 채 managed 엔티티를 로드 → 도메인 규칙으로 차감 →
        // 같은 managed 엔티티를 직접 수정해 dirty checking 으로 UPDATE(load-then-mutate).
        StockItemJpaEntity managed = queryRepository.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new StockNotFoundException(productId));
        StockItem stock = toDomain(managed);
        stock.reserve(quantity);   // 부족하면 InsufficientStockException
        managed.setAvailableQuantity(stock.getAvailableQuantity());
    }

    private StockItem toDomain(StockItemJpaEntity entity) {
        return new StockItem(entity.getProductId(), entity.getAvailableQuantity());
    }
}
