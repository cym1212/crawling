package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.CoupangInboundPlanItem;
import codeRecipe.crawling.crawling.domain.InboundPlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface CoupangInboundPlanItemRepository extends JpaRepository<CoupangInboundPlanItem, Long> {

    List<CoupangInboundPlanItem> findByPlanId(Long planId);

    /** 진행 중 계획에 이미 포함된 SKU 조회 (중복 신청 방지) */
    @Query("SELECT i.vendorItemId FROM CoupangInboundPlanItem i, CoupangInboundPlan p "
            + "WHERE p.planId = i.planId AND p.status IN :statuses AND i.vendorItemId IN :vendorItemIds")
    List<Long> findVendorItemIdsInPlans(@Param("vendorItemIds") Collection<Long> vendorItemIds,
                                        @Param("statuses") Collection<InboundPlanStatus> statuses);
}
