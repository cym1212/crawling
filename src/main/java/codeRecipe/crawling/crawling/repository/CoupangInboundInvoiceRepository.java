package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.CoupangInboundInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CoupangInboundInvoiceRepository extends JpaRepository<CoupangInboundInvoice, Long> {

    List<CoupangInboundInvoice> findByPlanIdOrderByBoxNoAsc(Long planId);

    Optional<CoupangInboundInvoice> findByPlanIdAndBoxNo(Long planId, Integer boxNo);
}
