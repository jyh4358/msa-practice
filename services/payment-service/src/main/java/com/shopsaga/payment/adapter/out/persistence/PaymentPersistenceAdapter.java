package com.shopsaga.payment.adapter.out.persistence;

import com.shopsaga.payment.application.port.out.SavePaymentPort;
import com.shopsaga.payment.domain.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 아웃바운드 영속 어댑터: SavePaymentPort 구현. 신규 결제 INSERT(도메인 id=null → @GeneratedValue). */
@Component
@RequiredArgsConstructor
class PaymentPersistenceAdapter implements SavePaymentPort {

    private final PaymentJpaRepository repository;

    @Override
    public Payment save(Payment payment) {
        return PaymentMapper.toDomain(repository.save(PaymentMapper.toJpaEntity(payment)));
    }
}
