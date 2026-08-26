package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.CoupangInboundSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CoupangInboundSettingRepository extends JpaRepository<CoupangInboundSetting, String> {
}
