package com.shopsaga.inventory.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ProcessedMessageJpaRepository extends JpaRepository<ProcessedMessageJpaEntity, UUID> {
}
