package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.CoupangFulfillmentCenter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CoupangFulfillmentCenterRepository extends JpaRepository<CoupangFulfillmentCenter, Long> {

    Optional<CoupangFulfillmentCenter> findByFcName(String fcName);
}
