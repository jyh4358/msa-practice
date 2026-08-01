package com.shopsaga.order.adapter.out.rest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.cloud.contract.stubrunner.junit.StubRunnerExtension;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;

import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 15: <b>소비자 쪽 계약 테스트</b> — inventory-service 없이, 그 서비스가 발행한 <b>계약</b>만으로 검증한다.
 *
 * <h2>이 테스트가 잡아내는 것</h2>
 * Phase 14에서 만든 {@link InventoryStockClient} 는 응답 JSON 을 {@code StockResponse} 로 역직렬화한다.
 * 만약 inventory 팀이 {@code availableQuantity} 를 {@code available} 로 개명하거나 문자열로 바꾸면,
 * <b>런타임에 프로덕션에서</b> 터질 일이 여기서 <b>빌드 시점에</b> 터진다.
 * 반대로 우리가 필드명을 잘못 적어도 즉시 드러난다.
 *
 * <h2>왜 Spring 컨텍스트를 띄우지 않는가</h2>
 * 계약이 검증하는 것은 <b>HTTP 표면 ↔ 우리 파싱 코드</b>의 합이다. 디스커버리·회로차단기·트랜잭션은 무관하다.
 * {@link StubRunnerExtension} 은 Spring 없이도 stub 서버를 띄워 주므로 테스트가 가볍고 빠르다.
 * (복원력 동작은 {@code StockAvailabilityRestAdapterTest} 가 따로 지킨다 — 관심사를 섞지 않는다.)
 *
 * <p>⚠️ stub 은 프로듀서가 {@code publishStubsPublicationToMavenLocal} 로 올린 것을 읽는다
 * ({@code stubsMode=LOCAL} = 로컬 Maven 저장소). 그 의존은 order-service 의 {@code test} 태스크에 걸려 있다.
 */
class InventoryContractConsumerTest {

    private static final int STUB_PORT = 8100;
    private static final UUID KNOWN = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID UNKNOWN = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @RegisterExtension
    static StubRunnerExtension stubRunner = new StubRunnerExtension()
            .downloadStub("com.shopsaga", "inventory-service", "0.0.1-SNAPSHOT", "stubs")
            .withPort(STUB_PORT)
            .stubsMode(StubRunnerProperties.StubsMode.LOCAL);

    /** 실제 프로덕션 클라이언트를 그대로 쓴다 — 테스트용 파싱 코드를 따로 두면 검증의 의미가 없다. */
    private InventoryStockClient client() {
        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + STUB_PORT)
                .build();
        return new InventoryStockClient(restClient, Runnable::run);   // 같은 스레드 실행(복원력 aspect 없이)
    }

    @Test
    void parsesProducerContract_forExistingProduct() {
        Integer available = client().availableQuantity(KNOWN, null).join();

        // 값(100)이 아니라 '숫자로 읽힌다'는 사실이 계약이다.
        assertThat(available).isNotNull().isNotNegative();
    }

    @Test
    void surfacesNotFound_forUnknownProduct() {
        // 404도 계약이다 — 소비자가 실패 형태를 상상하지 않도록 프로듀서가 명시했다.
        assertThatThrownBy(() -> client().availableQuantity(UNKNOWN, null).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(HttpClientErrorException.NotFound.class);
    }
}
