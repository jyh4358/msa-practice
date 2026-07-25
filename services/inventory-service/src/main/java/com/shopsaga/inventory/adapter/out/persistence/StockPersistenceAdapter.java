package com.shopsaga.inventory.adapter.out.persistence;

import com.shopsaga.inventory.application.port.out.LoadStockPort;
import com.shopsaga.inventory.application.port.out.ReleaseStockPort;
import com.shopsaga.inventory.application.port.out.ReserveStockPort;
import com.shopsaga.inventory.application.service.StockNotFoundException;
import com.shopsaga.inventory.domain.StockItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 아웃바운드 영속 어댑터: 재고 조회(LoadStockPort) + 예약(ReserveStockPort) + 해제(ReleaseStockPort).
 * 예약·해제 모두 비관적 락으로 행을 잠근 뒤 도메인 규칙을 적용한다.
 */
@Component
@RequiredArgsConstructor
class StockPersistenceAdapter implements LoadStockPort, ReserveStockPort, ReleaseStockPort {

    private final StockItemJpaRepository repository;
    private final StockQueryRepository queryRepository;

    @Override
    public Optional<StockItem> loadByProductId(UUID productId) {
        // 읽기 전용(재고 조회 엔드포인트) — 락 없음.
        return repository.findById(productId).map(this::toDomain);
    }

    @Override
    public void reserve(UUID productId, int quantity) {
        mutateLocked(productId, stock -> stock.reserve(quantity));
    }

    @Override
    public void release(UUID productId, int quantity) {
        // Phase 12 보상 — 예약과 같은 락 경로를 쓴다(동시 예약/해제가 섞여도 수량이 어긋나지 않게).
        mutateLocked(productId, stock -> stock.release(quantity));
    }

    /**
     * 비관적 쓰기 락으로 행을 잠근 채 managed 엔티티를 로드 → 도메인 규칙 적용 →
     * 같은 managed 엔티티를 직접 수정해 dirty checking 으로 UPDATE(load-then-mutate).
     */
    private void mutateLocked(UUID productId, java.util.function.Consumer<StockItem> mutation) {
        StockItemJpaEntity managed = queryRepository.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new StockNotFoundException(productId));
        StockItem stock = toDomain(managed);
        mutation.accept(stock);   // 부족하면 InsufficientStockException
        managed.setAvailableQuantity(stock.getAvailableQuantity());
    }

    private StockItem toDomain(StockItemJpaEntity entity) {
        return new StockItem(entity.getProductId(), entity.getAvailableQuantity());
    }
}
