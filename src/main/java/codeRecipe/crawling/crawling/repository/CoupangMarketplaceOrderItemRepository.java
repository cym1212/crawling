package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.CoupangMarketplaceOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CoupangMarketplaceOrderItemRepository extends JpaRepository<CoupangMarketplaceOrderItem, Long> {

    List<CoupangMarketplaceOrderItem> findByOrderId(Long orderId);

    List<CoupangMarketplaceOrderItem> findByOrderIdIn(List<Long> orderIds);
}
