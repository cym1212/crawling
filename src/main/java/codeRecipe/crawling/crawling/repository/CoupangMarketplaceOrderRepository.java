package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.CoupangMarketplaceOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CoupangMarketplaceOrderRepository extends JpaRepository<CoupangMarketplaceOrder, Long> {

    boolean existsByOrderId(Long orderId);
}
