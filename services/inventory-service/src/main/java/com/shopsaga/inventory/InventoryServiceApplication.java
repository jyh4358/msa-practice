package com.shopsaga.inventory;

import com.shopsaga.outbox.OutboxConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Phase 12: outbox·inbox 메커니즘이 공유 라이브러리로 옮겨졌으므로 엔티티·리포지토리 스캔 범위에
 * 자기 패키지와 함께 그 패키지를 명시한다({@code @EntityScan} 은 Boot 기본 스캔을 대체하므로 둘 다 필요).
 */
@SpringBootApplication
@EntityScan({"com.shopsaga.inventory", "com.shopsaga.outbox"})
@EnableJpaRepositories({"com.shopsaga.inventory", "com.shopsaga.outbox"})
@Import(OutboxConfiguration.class)
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
