package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.CoupangWingAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CoupangWingAddressRepository extends JpaRepository<CoupangWingAddress, Long> {

    Optional<CoupangWingAddress> findByAddressText(String addressText);

    List<CoupangWingAddress> findByAddressTextNotIn(Collection<String> addressTexts);
}
