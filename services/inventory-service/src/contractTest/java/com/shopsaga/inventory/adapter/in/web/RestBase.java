package com.shopsaga.inventory.adapter.in.web;

import com.shopsaga.inventory.application.port.in.GetStockQuery;
import com.shopsaga.inventory.application.port.in.StockView;
import com.shopsaga.inventory.application.service.StockNotFoundException;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

/**
 * Phase 15: <b>계약 검증 테스트의 베이스 클래스</b>.
 *
 * <p>{@code contracts/rest/*.yml} 로부터 자동 생성되는 테스트가 이 클래스를 상속한다
 * (규칙: {@code contracts/<폴더>} → {@code <packageWithBaseClasses>.<폴더>Base}).
 * 생성된 코드는 {@code build/generated-test-sources} 에서 볼 수 있다 — 한 번은 꼭 열어 볼 것.
 *
 * <h2>왜 애플리케이션 전체를 띄우지 않는가</h2>
 * 계약이 말하는 것은 <b>HTTP 표면</b>(경로·상태코드·응답 스키마)뿐이다. DB·Kafka·Eureka 는 계약과 무관하다.
 * 그래서 컨트롤러만 standalone MockMvc 로 세우고 유스케이스는 mock 으로 바꾼다 —
 * 계약 테스트가 <b>빠르고, 다른 이유로는 깨지지 않게</b> 하기 위해서다.
 * (여기서 통합까지 검증하려 들면 계약 테스트가 통합 테스트가 되어 버리고, 깨졌을 때 원인을 알 수 없게 된다.)
 *
 * <p>이 클래스가 컨트롤러와 <b>같은 패키지</b>에 있는 이유: {@code InventoryController} 와
 * {@code ApiExceptionHandler} 가 package-private 이기 때문이다(어댑터는 밖으로 새지 않는다는 규칙 유지).
 */
public abstract class RestBase {

    /** 계약에 등장하는 상품 — 시드 상품과 같은 id 를 써서 계약 문서와 실제가 어긋나지 않게 한다. */
    private static final UUID KNOWN = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID UNKNOWN = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        GetStockQuery getStockQuery = Mockito.mock(GetStockQuery.class);
        Mockito.when(getStockQuery.getStock(KNOWN)).thenReturn(new StockView(KNOWN, 100));
        Mockito.when(getStockQuery.getStock(UNKNOWN)).thenThrow(new StockNotFoundException(UNKNOWN));

        // Phase 14의 장애 주입 스위치는 계약과 무관 — 없는 것으로 둔다(mock 의 기본 동작 = 아무것도 안 함).
        ObjectProvider<ChaosSwitch> noChaos = Mockito.mock(ObjectProvider.class);

        RestAssuredMockMvc.mockMvc(
                MockMvcBuilders.standaloneSetup(new InventoryController(getStockQuery, noChaos))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build());
    }
}
