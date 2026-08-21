package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.CoupangInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CoupangInventoryRepository extends JpaRepository<CoupangInventory, Long> {

    Optional<CoupangInventory> findByVendorItemId(Long vendorItemId);

    @Query("SELECT MAX(c.collectedAt) FROM CoupangInventory c")
    LocalDateTime findMaxCollectedAt();

    List<CoupangInventory> findByCollectedAt(LocalDateTime collectedAt);
}
