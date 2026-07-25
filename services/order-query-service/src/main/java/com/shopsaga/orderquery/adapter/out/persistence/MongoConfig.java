package com.shopsaga.orderquery.adapter.out.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions.BigDecimalRepresentation;

/**
 * Phase 11: 읽기 모델 저장 시 <b>금액 표현</b>을 명시한다.
 *
 * <p>Spring Data MongoDB 4.5의 기본값은 {@code BigDecimal} → <b>문자열</b> 저장이다. 값은 보존되지만
 * 문자열이라 <b>숫자 비교·범위 쿼리·집계가 사전순</b>으로 어긋난다("9" &gt; "10"). 읽기 모델은 조회·집계가
 * 본업이므로 MongoDB 고유 10진 타입 {@code Decimal128}(= {@code NumberDecimal})로 저장한다
 * — 부동소수 오차 없이 금액을 다루는 타입이다.
 *
 * <p>Boot 자동구성을 대체하지 않고 컨버전만 커스터마이즈하므로 접속 설정(`spring.data.mongodb.uri`)은 그대로 쓰인다.
 * (UUID {@code @Id} 는 Boot 의 `spring.data.mongodb.uuid-representation` 기본값으로 처리된다 — 별도 설정 불필요.)
 */
@Configuration
class MongoConfig {

    @Bean
    MongoCustomConversions mongoCustomConversions() {
        return MongoCustomConversions.create(adapter ->
                adapter.bigDecimal(BigDecimalRepresentation.DECIMAL128));
    }
}
