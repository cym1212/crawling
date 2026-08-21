package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.CoupangRestockSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface CoupangRestockSuggestionRepository extends JpaRepository<CoupangRestockSuggestion, Long> {

    boolean existsByVendorItemIdAndSuggestionDate(Long vendorItemId, LocalDate suggestionDate);

    List<CoupangRestockSuggestion> findBySuggestionDate(LocalDate suggestionDate);
}
