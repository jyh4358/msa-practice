package com.shopsaga.inventory.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface StockReservationJpaRepository
        extends JpaRepository<StockReservationJpaEntity, StockReservationJpaEntity.Key> {

    List<StockReservationJpaEntity> findByOrderId(UUID orderId);

    void deleteByOrderId(UUID orderId);
}
