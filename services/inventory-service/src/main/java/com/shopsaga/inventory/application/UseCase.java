package com.shopsaga.inventory.application;

import org.springframework.stereotype.Service;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 애플리케이션 유스케이스 스테레오타입(= @Service). 헥사고날에서 애플리케이션 계층 컴포넌트 표식. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Service
public @interface UseCase {
}
