package com.shopsaga.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * 서비스 레지스트리 (Phase 4) — Eureka 서버.
 *
 * <p>각 서비스는 {@code spring-cloud-starter-netflix-eureka-client} 만 추가하면 부팅 시
 * 자기 이름(spring.application.name)으로 여기에 등록되고, 다른 서비스를 이름으로 찾는다.
 * 게이트웨이는 {@code lb://order-service}, order→payment 는 {@code http://payment-service} 로 호출한다.
 * 즉 이 단계부터 "어디(host:port)" 대신 "누구(서비스 이름)"로 통신한다.
 */
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServiceApplication.class, args);
    }
}
