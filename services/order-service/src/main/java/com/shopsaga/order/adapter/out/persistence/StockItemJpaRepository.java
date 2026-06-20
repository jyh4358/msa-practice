package com.shopsaga.order.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface StockItemJpaRepository extends JpaRepository<StockItemJpaEntity, UUID> {
}
