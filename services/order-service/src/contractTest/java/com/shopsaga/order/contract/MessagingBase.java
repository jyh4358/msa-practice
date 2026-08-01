package com.shopsaga.order.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.events.Topics;
import com.shopsaga.outbox.OutboxMessage;
import com.shopsaga.outbox.OutboxRepository;
import com.shopsaga.outbox.OutboxWriter;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.verifier.converter.YamlContract;
import org.springframework.cloud.contract.verifier.messaging.MessageVerifierReceiver;
import org.springframework.cloud.contract.verifier.messaging.MessageVerifierSender;
import org.springframework.cloud.contract.verifier.messaging.boot.AutoConfigureMessageVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Phase 15: <b>이벤트 계약 검증의 베이스 클래스</b>({@code contracts/messaging/*.yml} → {@code MessagingBase}).
 *
 * <h2>여기서 배우는 것 — 계약 검증기는 갈아 끼울 수 있다</h2>
 * Spring Cloud Contract 4.3은 메시징 통합으로 Camel·Spring Integration·Spring Cloud Stream·JMS 를 제공한다.
 * <b>Apache Kafka 전용 통합은 없다.</b> 우리는 KafkaTemplate 을 직접 쓰므로 기성품이 맞지 않는다.
 *
 * <p>그래서 {@link MessageVerifierReceiver} 를 <b>직접 구현</b>해 끼운다. 그런데 어디서 메시지를 "받을" 것인가?
 * 이 플랫폼에서 이벤트의 <b>발신함은 Kafka 가 아니라 outbox 테이블</b>이다(Phase 10~12).
 * 서비스가 "이 이벤트를 보내겠다"고 결정한 지점이 곧 outbox row 이고,
 * 그 row 의 payload 는 <b>실제 발행에 쓰이는 바로 그 JSON</b>이다.
 * 따라서 outbox 를 읽으면 브로커 없이도 진짜 직렬화 결과를 계약과 대조할 수 있다.
 *
 * <p><b>이 선택의 한계(정직하게):</b> 브로커까지의 왕복(파티션 키·헤더·릴레이 동작)은 검증하지 않는다.
 * 그건 Phase 12~14의 compose 실증이 담당한다. 계약 테스트는 <b>스키마</b>에만 집중한다.
 *
 * <p>⚠️ 검증기의 제네릭 타입이 {@code Message<?>}(스프링 메시징)인 이유: Phase 15에서 Bus 를 넣으며
 * spring-cloud-stream 이 클래스패스에 들어왔고, 그러면 SCC 의 스트림용 자동설정이 활성화되어
 * {@code MessageVerifierSender<Message<?>>} 를 요구한다. 타입이 어긋나면
 * {@code NoSuchBeanDefinitionException} 으로 컨텍스트가 뜨지 않는다(실제로 겪었다).
 */
@SpringBootTest(classes = MessagingBase.ContractTestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureMessageVerifier
public abstract class MessagingBase {

    private static final UUID CUSTOMER = UUID.fromString("cccc1111-1111-1111-1111-111111111111");
    private static final UUID PRODUCT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private OutboxWriter outboxWriter;

    /**
     * 계약의 {@code triggeredBy} 가 호출하는 메서드 — "이 상황이 되면 그 메시지가 나간다"를 재현한다.
     * 실제 유스케이스(DB·Saga)까지 끌어오지 않는 이유는 계약이 <b>스키마</b>만 말하기 때문이다.
     */
    public void orderPlacedTriggered() {
        UUID orderId = UUID.randomUUID();
        outboxWriter.write(orderId, new OrderPlacedEvent(
                orderId, CUSTOMER,
                List.of(new OrderPlacedEvent.Item(PRODUCT, 2, new BigDecimal("10.00"))),
                new BigDecimal("20.00"), Instant.parse("2026-07-29T00:00:00Z")), Topics.ORDER_EVENTS);
    }

    /** outbox 를 "발신함"으로 삼는 테스트 컨텍스트. */
    @Configuration
    @Import(JacksonAutoConfiguration.class)   // 프로덕션과 같은 ObjectMapper 설정을 써야 계약 검증이 의미 있다
    static class ContractTestConfig {

        /** 기록된 outbox row 를 그대로 들고 있는 인메모리 발신함(DB 없이 진짜 직렬화 경로만 검증). */
        @Bean
        List<OutboxMessage> sentBox() {
            return new ArrayList<>();
        }

        @Bean
        OutboxRepository outboxRepository(List<OutboxMessage> sentBox) {
            OutboxRepository repository = Mockito.mock(OutboxRepository.class);
            Mockito.when(repository.save(Mockito.any(OutboxMessage.class))).thenAnswer(invocation -> {
                OutboxMessage saved = invocation.getArgument(0);
                sentBox.add(saved);
                return saved;
            });
            return repository;
        }

        @Bean
        OutboxWriter outboxWriter(OutboxRepository repository, ObjectMapper objectMapper) {
            return new OutboxWriter(repository, objectMapper, null, null);   // 트레이싱은 계약과 무관
        }

        /**
         * 생성된 테스트의 {@code contractVerifierMessaging.receive("order-events", …)} 가 여기로 온다.
         * 해당 토픽으로 기록된 outbox row 의 payload 를 파싱해 돌려주면, SCC 가 계약의 matcher 로 대조한다.
         */
        @Bean
        MessageVerifierReceiver<Message<?>> contractMessageReceiver(
                List<OutboxMessage> sentBox, ObjectMapper objectMapper) {
            return new MessageVerifierReceiver<>() {
                @Override
                public Message<?> receive(String destination, long timeout, TimeUnit unit, YamlContract contract) {
                    return sentBox.stream()
                            .filter(m -> destination.equals(m.getTopic()))
                            .findFirst()
                            .map(m -> toMessage(m, objectMapper))
                            .orElse(null);
                }

                @Override
                public Message<?> receive(String destination, YamlContract contract) {
                    return receive(destination, 5, TimeUnit.SECONDS, contract);
                }
            };
        }

        /** 우리는 아웃바운드 메시지만 계약한다(이 서비스는 이 계약의 발신자다) → 송신기는 쓰이지 않는다. */
        @Bean
        MessageVerifierSender<Message<?>> contractMessageSender() {
            return new MessageVerifierSender<>() {
                @Override
                public void send(Message<?> message, String destination, YamlContract contract) {
                    throw new UnsupportedOperationException("이 계약은 출력 메시지만 검증한다");
                }

                @Override
                public <T> void send(T payload, Map<String, Object> headers, String destination,
                                     YamlContract contract) {
                    throw new UnsupportedOperationException("이 계약은 출력 메시지만 검증한다");
                }
            };
        }

        @SuppressWarnings("unchecked")
        private static Message<?> toMessage(OutboxMessage row, ObjectMapper objectMapper) {
            try {
                Map<String, Object> payload = objectMapper.readValue(row.getPayload(), Map.class);
                // 실제 발행 시 붙는 헤더도 함께 노출 — 계약이 헤더까지 검증할 수 있게.
                return MessageBuilder.withPayload(payload)
                        .setHeader("messageId", row.getId().toString())
                        .setHeader("eventType", row.getEventType())
                        .build();
            } catch (Exception e) {
                throw new IllegalStateException("outbox payload 파싱 실패", e);
            }
        }
    }
}
