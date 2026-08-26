package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.CoupangInboundPlan;
import codeRecipe.crawling.crawling.domain.InboundPlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface CoupangInboundPlanRepository extends JpaRepository<CoupangInboundPlan, Long> {

    List<CoupangInboundPlan> findByStatusInOrderByRequestedAtAsc(Collection<InboundPlanStatus> statuses);
}
