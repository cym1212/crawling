package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.CoupangMarketplaceOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CoupangMarketplaceOrderRepository extends JpaRepository<CoupangMarketplaceOrder, Long> {

    boolean existsByOrderId(Long orderId);

    Optional<CoupangMarketplaceOrder> findByOrderId(Long orderId);

    /** 아직 다이제스트로 알리지 않은 주문 (결제 오래된 순) */
    List<CoupangMarketplaceOrder> findByNotifiedAtIsNullOrderByPaidAtAsc();
}
