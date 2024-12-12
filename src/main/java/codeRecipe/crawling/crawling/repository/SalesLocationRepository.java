package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.SalesLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SalesLocationRepository extends JpaRepository<SalesLocation, Long> {
    boolean existsByLocationNameAndRegion(String locationName, String region);
    Optional<SalesLocation> findByLocationNameAndRegion(String locationName, String region);
    List<SalesLocation> findAllByLocationNameAndRegionIn(String locationName, List<String> regions);
}
