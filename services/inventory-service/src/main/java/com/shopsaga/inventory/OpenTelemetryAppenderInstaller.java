package com.shopsaga.inventory;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Phase 8b: Logback 의 OTel appender 에 OpenTelemetry 인스턴스를 주입한다(컨텍스트 기동 후 install()).
 * 호출 전엔 appender 가 no-op 이라 로그가 OTLP 로 전송되지 않는다.
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
