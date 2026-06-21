package com.shopsaga.order.adapter.out.persistence;

import com.shopsaga.order.application.port.out.LoadStockPort;
import com.shopsaga.order.application.port.out.ReserveStockPort;
import com.shopsaga.order.application.service.StockNotFoundException;
import com.shopsaga.order.domain.StockItem;
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
        // 비관적 쓰기 락으로 행을 잠근 채 '관리(managed)' 엔티티를 로드 → 도메인 규칙으로 차감 →
        // 같은 managed 엔티티를 직접 수정해 dirty checking 으로 UPDATE(락 걸린 그 행에 그대로 반영).
        // 새 엔티티 merge 가 아니라 load-then-mutate 라, 락↔UPDATE 결합이 구조적으로 보장된다.
        StockItemJpaEntity managed = queryRepository.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new StockNotFoundException(productId));
        StockItem stock = toDomain(managed);
        stock.reserve(quantity);   // 부족하면 InsufficientStockException → 트랜잭션 롤백
        managed.setAvailableQuantity(stock.getAvailableQuantity());
    }

    private StockItem toDomain(StockItemJpaEntity entity) {
        return new StockItem(entity.getProductId(), entity.getAvailableQuantity());
    }
}
