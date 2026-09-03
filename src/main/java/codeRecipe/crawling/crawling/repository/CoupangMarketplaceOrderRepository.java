package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.CoupangMarketplaceOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CoupangMarketplaceOrderRepository extends JpaRepository<CoupangMarketplaceOrder, Long> {

    boolean existsByOrderId(Long orderId);

    boolean existsByOrderIdAndShipmentBoxId(Long orderId, Long shipmentBoxId);

    /** 한 주문의 배송박스 행 전체 (배송비 그룹이 다르면 여러 개) */
    List<CoupangMarketplaceOrder> findAllByOrderId(Long orderId);

    /** 발주확인 일괄 처리용 (shipmentBoxId는 쿠팡 전역 유니크) */
    List<CoupangMarketplaceOrder> findByShipmentBoxIdIn(java.util.Collection<Long> shipmentBoxIds);

    /** 아직 다이제스트로 알리지 않은 주문 (결제 오래된 순) */
    List<CoupangMarketplaceOrder> findByNotifiedAtIsNullOrderByPaidAtAsc();

    /** 송장 미등록(미출고) 주문 — 상태 동기화·미처리 재알림 대상 (취소 여부는 서비스에서 걸러냄) */
    List<CoupangMarketplaceOrder> findByShippedAtIsNullOrderByPaidAtAsc();
}
