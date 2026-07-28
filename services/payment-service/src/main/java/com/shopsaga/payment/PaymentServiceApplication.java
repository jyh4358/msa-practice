package com.shopsaga.payment;

import com.shopsaga.messaging.KafkaErrorHandlingConfiguration;
import com.shopsaga.outbox.OutboxConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Phase 12: 결제가 Saga 참여자가 되면서 outbox·inbox 메커니즘(공유 라이브러리)을 쓴다.
 * {@code @EntityScan}/{@code @EnableJpaRepositories} 는 Boot 기본 스캔을 대체하므로 자기 패키지도 함께 명시한다.
 */
@SpringBootApplication
@EntityScan({"com.shopsaga.payment", "com.shopsaga.outbox"})
@EnableJpaRepositories({"com.shopsaga.payment", "com.shopsaga.outbox"})
@Import({OutboxConfiguration.class, KafkaErrorHandlingConfiguration.class})   // Phase 14: 소비 실패 → DLQ
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
