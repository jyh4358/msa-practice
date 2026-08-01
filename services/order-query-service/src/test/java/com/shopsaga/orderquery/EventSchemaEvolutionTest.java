package com.shopsaga.orderquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.shopsaga.events.OrderPlacedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.JacksonUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Phase 15: <b>이벤트 스키마 진화 규칙을 코드로 못 박는 테스트</b>.
 *
 * <h2>왜 필요한가</h2>
 * 이벤트는 <b>배포 시점이 서로 다른</b> 서비스들이 주고받는다. 프로듀서가 먼저 올라가고 소비자가 나중에 올라가거나,
 * 그 반대이거나, 혹은 <b>과거에 발행된 이벤트를 리플레이</b>(Phase 11)하면 옛 스키마가 되살아난다.
 * 즉 어떤 순간에도 <b>여러 버전이 동시에 흐른다</b>고 가정해야 한다.
 *
 * <h2>규칙 — tolerant reader</h2>
 * <ul>
 *   <li>✅ <b>필드 추가</b>는 허용한다. 옛 소비자는 모르는 필드를 <b>무시</b>해야 한다.</li>
 *   <li>❌ <b>제거·개명·타입 변경</b>은 금지한다. 소비자가 읽던 것이 사라지기 때문이다.</li>
 * </ul>
 *
 * <p>⚠️ 이 테스트가 쓰는 매퍼는 {@link JacksonUtils#enhancedObjectMapper()} — spring-kafka 의
 * {@code JsonDeserializer} 가 <b>실제 런타임에 쓰는 바로 그 설정</b>이다.
 * 직접 만든 {@code new ObjectMapper()} 로 테스트하면 런타임과 다른 것을 검증하게 된다.
 */
class EventSchemaEvolutionTest {

    /** 소비 측 런타임과 동일한 매퍼(FAIL_ON_UNKNOWN_PROPERTIES 비활성 + JavaTimeModule 등록됨). */
    private final ObjectMapper consumerMapper = JacksonUtils.enhancedObjectMapper();

    private static final String CURRENT = """
            {"orderId":"0f3a2f6c-0000-4000-8000-000000000001",
             "customerId":"cccc1111-1111-1111-1111-111111111111",
             "totalAmount":20.00,
             "occurredAt":"2026-07-29T00:00:00Z",
             "items":[{"productId":"22222222-2222-2222-2222-222222222222","quantity":2,"unitPrice":10.00}]}
            """;

    @Test
    @DisplayName("기준: 현재 스키마는 당연히 읽힌다")
    void currentSchema_isReadable() throws Exception {
        OrderPlacedEvent event = consumerMapper.readValue(CURRENT, OrderPlacedEvent.class);

        assertThat(event.totalAmount()).isEqualByComparingTo("20.00");
        assertThat(event.items()).singleElement()
                .satisfies(i -> assertThat(i.quantity()).isEqualTo(2));
    }

    @Test
    @DisplayName("✅ 호환: 프로듀서가 필드를 '추가'해도 소비자는 무시하고 계속 읽는다")
    void unknownField_isIgnored() {
        // 새 프로듀서가 couponCode 를 붙여 발행했고, 아직 그걸 모르는 소비자가 받는 상황.
        String withNewField = CURRENT.replace("\"totalAmount\":20.00",
                "\"totalAmount\":20.00,\"couponCode\":\"SUMMER25\"");

        assertThatCode(() -> consumerMapper.readValue(withNewField, OrderPlacedEvent.class))
                .doesNotThrowAnyException();
        // ★ 이것이 tolerant reader 다. FAIL_ON_UNKNOWN_PROPERTIES 가 켜져 있으면 여기서 터지고,
        //   프로듀서는 소비자를 전부 먼저 배포하기 전에는 필드 하나도 못 늘리게 된다.
    }

    @Test
    @DisplayName("✅ 호환: 옛 프로듀서가 보낸 '필드 없는' 이벤트도 읽히지만 — null 을 견뎌야 한다")
    void missingField_readsAsNull_soConsumersMustTolerateIt() throws Exception {
        // Phase 11 이전의 OrderPlaced 에는 totalAmount·occurredAt 이 없었다(리플레이하면 이 모양이 되살아난다).
        String legacy = """
                {"orderId":"0f3a2f6c-0000-4000-8000-000000000001",
                 "customerId":"cccc1111-1111-1111-1111-111111111111",
                 "items":[{"productId":"22222222-2222-2222-2222-222222222222","quantity":2,"unitPrice":10.00}]}
                """;

        OrderPlacedEvent event = consumerMapper.readValue(legacy, OrderPlacedEvent.class);

        // 역직렬화는 성공한다 — 하지만 값은 null 이다.
        assertThat(event.totalAmount()).isNull();
        assertThat(event.occurredAt()).isNull();
        // ⚠️ 그래서 "필드 추가는 안전하다"는 말은 반쪽이다. 소비자가 그 필드를 필수로 쓰고 있으면
        //    옛 이벤트를 만나는 순간 NPE 로 죽는다 → Phase 14의 DLT 로 흘러간다.
        //    새 필드를 도입할 때는 '기본값을 정해 두거나' 소비자가 null 을 처리해야 한다.
    }

    @Test
    @DisplayName("❌ 파괴적: 필드를 '개명'하면 예외가 아니라 조용한 데이터 유실로 나타난다")
    void renamedField_isSilentDataLoss() throws Exception {
        // 프로듀서가 totalAmount → total 로 바꿨다. 소비자는 아무 에러도 못 본다.
        String renamed = CURRENT.replace("\"totalAmount\"", "\"total\"");

        OrderPlacedEvent event = consumerMapper.readValue(renamed, OrderPlacedEvent.class);

        // ★ 가장 위험한 시나리오 — 시스템은 '정상 동작'하는데 금액만 사라진다.
        //   읽기 모델에는 총액 없는 주문이 쌓이고, 아무도 즉시 알아채지 못한다.
        //   개명이 '제거 + 추가'와 같다는 사실이 여기서 드러난다.
        assertThat(event.totalAmount()).isNull();
    }

    @Test
    @DisplayName("❌ 파괴적: 타입을 바꾸면 역직렬화가 실패한다(→ Phase 14의 DLT 행)")
    void typeChange_breaksDeserialization() {
        // quantity 를 숫자에서 문자열 표현으로 바꾼 경우("2"는 관대하게 강제변환되므로 진짜 문자열로)
        String typeChanged = CURRENT.replace("\"quantity\":2", "\"quantity\":\"two\"");

        assertThatThrownBy(() -> consumerMapper.readValue(typeChanged, OrderPlacedEvent.class))
                .isInstanceOf(InvalidFormatException.class);
        // 이 실패는 '조용하지 않다'는 점에서 개명보다 낫다 — 소비자가 즉시 멈추고 DLT 에 증거가 남는다.
    }

    @Test
    @DisplayName("소비 측 매퍼는 실제로 tolerant 하게 설정돼 있다")
    void consumerMapperIsConfiguredTolerant() {
        assertThat(consumerMapper.getDeserializationConfig()
                .isEnabled(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                .as("이 값이 true 가 되면 프로듀서가 필드를 추가하는 순간 전 소비자가 깨진다")
                .isFalse();
    }
}
