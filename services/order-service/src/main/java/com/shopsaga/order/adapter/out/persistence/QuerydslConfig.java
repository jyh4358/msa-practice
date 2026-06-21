package com.shopsaga.order.adapter.out.persistence;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** QueryDSL 팩토리 빈(영속 어댑터 내부 설정). 현재 트랜잭션의 EntityManager를 사용한다. */
@Configuration
class QuerydslConfig {

    @Bean
    JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
