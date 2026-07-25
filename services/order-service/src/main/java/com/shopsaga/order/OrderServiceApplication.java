package com.shopsaga.order;

import com.shopsaga.outbox.OutboxConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Phase 12: outbox·inbox 메커니즘이 공유 라이브러리(`com.shopsaga.outbox`)로 옮겨졌으므로
 * 엔티티·리포지토리 스캔 범위에 <b>자기 패키지와 함께</b> 그 패키지를 명시한다.
 * ({@code @EntityScan}/{@code @EnableJpaRepositories} 는 Boot 기본 스캔을 대체하므로 둘 다 적어야 한다.)
 * {@code @EnableScheduling} 은 {@link OutboxConfiguration} 이 제공한다(릴레이 폴링).
 */
@SpringBootApplication
@EntityScan({"com.shopsaga.order", "com.shopsaga.outbox"})
@EnableJpaRepositories({"com.shopsaga.order", "com.shopsaga.outbox"})
@Import(OutboxConfiguration.class)
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
