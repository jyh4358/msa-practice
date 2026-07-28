package com.shopsaga.order.application.port.out;

import com.shopsaga.events.commands.ChargePaymentCommand;
import com.shopsaga.events.commands.ReleaseStockCommand;
import com.shopsaga.events.commands.ReserveStockCommand;

/**
 * 아웃바운드 포트: 조정자가 참여 서비스에게 보내는 <b>커맨드</b> 발행(Phase 13).
 * outbox 로 기록되므로 상태 전이와 커맨드 발행이 원자적이다(Phase 10의 장치를 그대로 재사용).
 */
public interface PublishSagaCommandPort {

    void reserveStock(ReserveStockCommand command);

    void chargePayment(ChargePaymentCommand command);

    void releaseStock(ReleaseStockCommand command);
}
