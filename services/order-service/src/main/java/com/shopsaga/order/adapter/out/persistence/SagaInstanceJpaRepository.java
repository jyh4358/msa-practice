package com.shopsaga.order.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SagaInstanceJpaRepository extends JpaRepository<SagaInstanceJpaEntity, UUID> {

    Optional<SagaInstanceJpaEntity> findByOrderId(UUID orderId);
}
