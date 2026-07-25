package com.shopsaga.orderquery.adapter.out.persistence;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 11: 읽기 모델의 MongoDB 문서.
 *
 * <p><b>왜 문서형인가:</b> 읽기 모델은 "화면이 원하는 모양"이고, 그 모양은 보통 <b>중첩 구조</b>다
 * (주문 1건 + 품목 N줄). 관계형이면 조인 2회지만, 문서형은 이 문서 하나를 읽으면 끝난다.
 *
 * <p>{@code @Id} 를 orderId 로 삼는 것이 핵심이다 → {@code save()} 가 <b>같은 _id 를 덮어쓰는 upsert</b> 가
 * 되어 투영이 자동으로 멱등해진다(리플레이·중복 소비에 안전).
 */
@Document(collection = "order_views")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class OrderViewDocument {

    /** = orderId. 덮어쓰기(upsert) 키 → 투영 멱등성의 근거. */
    @Id
    private UUID orderId;

    @Indexed   // 고객별 조회(findByCustomerId)를 위한 인덱스
    private UUID customerId;

    private String status;

    private BigDecimal totalAmount;   // Decimal128 로 저장(MongoConfig 에서 표현 방식 명시)

    private Instant placedAt;

    private List<LineDocument> lines;   // 중첩 배열로 문서 안에 함께 저장(비정규화)

    OrderViewDocument(UUID orderId, UUID customerId, String status, BigDecimal totalAmount,
                      Instant placedAt, List<LineDocument> lines) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.placedAt = placedAt;
        this.lines = lines;
    }

    /** 중첩 문서(별도 컬렉션 아님 — @Document 불필요). */
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    static class LineDocument {

        private UUID productId;
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;

        LineDocument(UUID productId, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
            this.productId = productId;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.lineTotal = lineTotal;
        }
    }
}
