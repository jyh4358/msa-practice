package com.shopsaga.auth;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Phase 8b: Logback 의 OTel appender 에 OpenTelemetry 인스턴스를 주입한다.
 *
 * <p>Logback 은 스프링 컨텍스트보다 먼저 초기화되므로, XML 선언만으로는 appender 가
 * OpenTelemetry 를 얻지 못한다(no-op). 컨텍스트가 뜬 뒤 {@code install()} 을 호출해야
 * 로그가 실제로 OTLP 로 전송된다(→ otel-lgtm Loki).
 */
@Component
class OpenTelemetryAppenderInstaller implements InitializingBean {

    private final OpenTelemetry openTelemetry;

    OpenTelemetryAppenderInstaller(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(this.openTelemetry);
    }
}
