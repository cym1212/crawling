package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.CoupangRestockSuggestion;
import codeRecipe.crawling.crawling.domain.RestockStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface CoupangRestockSuggestionRepository extends JpaRepository<CoupangRestockSuggestion, Long> {

    boolean existsByVendorItemIdAndSuggestionDate(Long vendorItemId, LocalDate suggestionDate);

    List<CoupangRestockSuggestion> findBySuggestionDate(LocalDate suggestionDate);

    /** 입고 계획 생성/완료 시 제안 상태 전환용 (의뢰 #8) */
    List<CoupangRestockSuggestion> findByVendorItemIdInAndStatus(Collection<Long> vendorItemIds, RestockStatus status);
}
