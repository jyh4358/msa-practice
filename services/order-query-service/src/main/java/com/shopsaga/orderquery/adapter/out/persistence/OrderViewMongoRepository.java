package com.shopsaga.orderquery.adapter.out.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.UUID;

interface OrderViewMongoRepository extends MongoRepository<OrderViewDocument, UUID> {

    /** 파생 쿼리 — 최근 주문 먼저. (customerId 는 @Indexed) */
    List<OrderViewDocument> findByCustomerIdOrderByPlacedAtDesc(UUID customerId);
}
