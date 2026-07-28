package com.shopsaga.orderquery;

import com.shopsaga.messaging.KafkaErrorHandlingConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Phase 14: 읽기 모델은 이벤트를 <b>소비만</b> 한다(발행 없음 → outbox 불필요).
 * 대신 투영 중 실패가 파티션을 막지 않도록 DLQ 설정을 가져온다.
 */
@SpringBootApplication
@Import(KafkaErrorHandlingConfiguration.class)
public class OrderQueryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderQueryServiceApplication.class, args);
    }
}
