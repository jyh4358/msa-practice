package com.shopsaga.order.adapter.out.persistence;

import com.shopsaga.order.application.port.out.LoadStockPort;
import com.shopsaga.order.application.port.out.SaveStockPort;
import com.shopsaga.order.domain.StockItem;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** 아웃바운드 영속 어댑터: 재고 Load/Save 포트 구현. */
@Component
class StockPersistenceAdapter implements LoadStockPort, SaveStockPort {

    private final StockItemJpaRepository repository;

    StockPersistenceAdapter(StockItemJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<StockItem> loadByProductId(UUID productId) {
        return repository.findById(productId)
                .map(e -> new StockItem(e.getProductId(), e.getAvailableQuantity()));
    }

    @Override
    public void save(StockItem stockItem) {
        // product_id 는 앱 할당 식별자(HEXAGONAL.md §3.2 "assigned id" 사례). 새 엔티티로 save()하면 merge:
        // 행이 있으면 SELECT→UPDATE. 여기선 V2가 모든 재고 행을 미리 시드하고 차감(UPDATE)만 하므로 안전하다
        // (INSERT 경로 없음 — 미존재 상품은 호출 전에 StockNotFound→404). 비용: merge 의 사전 SELECT 1회.
        // 제거하려면 §3.3 load-then-mutate(dirty checking).
        repository.save(new StockItemJpaEntity(stockItem.getProductId(), stockItem.getAvailableQuantity()));
    }
}
