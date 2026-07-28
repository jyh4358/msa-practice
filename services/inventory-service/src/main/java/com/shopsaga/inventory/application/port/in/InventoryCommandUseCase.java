package com.shopsaga.inventory.application.port.in;

import com.shopsaga.events.commands.ReleaseStockCommand;
import com.shopsaga.events.commands.ReserveStockCommand;

/**
 * Phase 13: 재고 서비스의 <b>커맨드 핸들러</b> — 조정자의 지시를 받아 수행하고 결과를 돌려준다.
 *
 * <p>Phase 12의 {@code InventorySagaUseCase} 와 비교하면 이 Phase의 교훈이 보인다:
 * <ul>
 *   <li>Phase 12: {@code onOrderPlaced}·{@code onPaymentDeclined} — <b>남의 사실</b>을 듣고 스스로 판단했다.
 *       "결제가 거절됐으니 재고를 풀어야겠다"는 결정을 재고 서비스가 내렸다.</li>
 *   <li>Phase 13: {@code onReserveStock}·{@code onReleaseStock} — <b>지시받은 일</b>만 한다.
 *       왜 푸는지는 몰라도 되고, 알 필요도 없다("멍청한" 핸들러).</li>
 * </ul>
 * 결합의 방향이 바뀐 것이다: 사실에 대한 해석 책임이 참여자에서 조정자로 옮겨갔다.
 */
public interface InventoryCommandUseCase {

    void onReserveStock(ReserveStockCommand command);

    void onReleaseStock(ReleaseStockCommand command);
}
