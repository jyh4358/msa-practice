package com.shopsaga.order.application;

import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 애플리케이션 유스케이스 구현을 표시하는 스테레오타입(= @Component).
 * @Service 대신 의도를 드러내는 헥사고날 관용 표기.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface UseCase {
}
