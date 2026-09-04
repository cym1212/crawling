package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.CoupangWingAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CoupangWingAddressRepository extends JpaRepository<CoupangWingAddress, Long> {

    Optional<CoupangWingAddress> findByAddressText(String addressText);

    List<CoupangWingAddress> findByAddressTextNotIn(Collection<String> addressTexts);

    /** 마지막 동기화 시각 — 오래되면 재동기화 작업을 내려보낸다 */
    @Query("SELECT MAX(a.syncedAt) FROM CoupangWingAddress a")
    LocalDateTime findMaxSyncedAt();
}
