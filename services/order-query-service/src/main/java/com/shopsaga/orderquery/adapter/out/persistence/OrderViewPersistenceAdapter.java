package com.shopsaga.orderquery.adapter.out.persistence;

import com.shopsaga.orderquery.application.port.out.OrderViewRepositoryPort;
import com.shopsaga.orderquery.domain.OrderView;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 아웃바운드 어댑터: 읽기 모델을 MongoDB 문서로 저장/조회하고 도메인 객체로 되돌린다. */
@Component
@RequiredArgsConstructor
class OrderViewPersistenceAdapter implements OrderViewRepositoryPort {

    private static final String FIELD_ID = "_id";
    private static final String FIELD_STATUS = "status";

    private final OrderViewMongoRepository repository;
    private final MongoTemplate mongoTemplate;

    @Override
    public void upsertBase(OrderView view, String initialStatus) {
        // 본문 필드만 $set 하고, status 는 $setOnInsert → 신규 생성 시에만 초기값이 들어간다.
        // (이미 진행된 주문에 OrderPlaced 가 재배달돼도 상태가 PENDING 으로 되돌아가지 않는다.)
        Update update = new Update()
                .set("customerId", view.getCustomerId())
                .set("totalAmount", view.getTotalAmount())
                .set("placedAt", view.getPlacedAt())
                .set("lines", toLineDocuments(view))
                .setOnInsert(FIELD_STATUS, initialStatus);
        mongoTemplate.upsert(
                Query.query(Criteria.where(FIELD_ID).is(view.getOrderId())),
                update,
                OrderViewDocument.class);
    }

    @Override
    public boolean applyStatusIfCurrentIn(UUID orderId, String newStatus, Set<String> overwritable) {
        if (overwritable.isEmpty()) {
            return false;   // 덮어쓸 수 있는 상태가 없음(최하위 순위) — 전이 대상 아님
        }
        // 조건(현재 상태가 덮어쓸 수 있는 값 중 하나) + 갱신을 DB 한 연산으로 → 경합에도 단조성 보장.
        // upsert 가 아니라 update 이므로 문서가 없으면 아무 일도 하지 않는다(matched=0).
        var result = mongoTemplate.updateFirst(
                Query.query(Criteria.where(FIELD_ID).is(orderId).and(FIELD_STATUS).in(overwritable)),
                new Update().set(FIELD_STATUS, newStatus),
                OrderViewDocument.class);
        return result.getModifiedCount() > 0;
    }

    @Override
    public Optional<OrderView> findByOrderId(UUID orderId) {
        return repository.findById(orderId).map(this::toDomain);
    }

    @Override
    public List<OrderView> findByCustomerId(UUID customerId) {
        return repository.findByCustomerIdOrderByPlacedAtDesc(customerId).stream()
                .map(this::toDomain)
                .toList();
    }

    private List<OrderViewDocument.LineDocument> toLineDocuments(OrderView view) {
        return view.getLines().stream()
                .map(l -> new OrderViewDocument.LineDocument(
                        l.getProductId(), l.getQuantity(), l.getUnitPrice(), l.getLineTotal()))
                .toList();
    }

    private OrderView toDomain(OrderViewDocument doc) {
        List<OrderView.Line> lines = doc.getLines().stream()
                .map(l -> new OrderView.Line(l.getProductId(), l.getQuantity(), l.getUnitPrice()))
                .toList();
        return new OrderView(doc.getOrderId(), doc.getCustomerId(), doc.getStatus(),
                doc.getTotalAmount(), doc.getPlacedAt(), lines);
    }
}
