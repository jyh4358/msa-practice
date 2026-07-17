package com.shopsaga.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * 중앙 설정 서버 (Phase 6) — Spring Cloud Config Server.
 *
 * <p>native 백엔드로 모노레포 루트의 {@code config-repo/} 디렉터리를 읽어
 * {@code GET /{application}/{profile}} 로 병합된 설정을 서빙한다.
 * DB 비밀번호는 {@code {cipher}} 로 암호화되어 저장되고, 서버가 응답 전 대칭키로 복호화한다
 * (키는 파일이 아니라 {@code ENCRYPT_KEY} 환경변수로만 주입 — 키를 리포지토리에 남기지 않는다).
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServiceApplication.class, args);
    }
}
