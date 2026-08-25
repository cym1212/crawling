package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.HottracksPurchaseOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HottracksPurchaseOrderItemRepository extends JpaRepository<HottracksPurchaseOrderItem, Long> {

    List<HottracksPurchaseOrderItem> findByPurchaseOrderId(Long purchaseOrderId);

    List<HottracksPurchaseOrderItem> findByPurchaseOrderIdIn(List<Long> purchaseOrderIds);
}
