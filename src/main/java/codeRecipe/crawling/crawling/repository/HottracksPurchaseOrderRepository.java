package codeRecipe.crawling.crawling.repository;

import codeRecipe.crawling.crawling.domain.HottracksPurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HottracksPurchaseOrderRepository extends JpaRepository<HottracksPurchaseOrder, Long> {

    /** 발주키(지점코드+발주일+발주번호)로 중복 여부 확인 (수집 시) */
    boolean existsByPlorRdpCodeAndPlorDateAndPlorNum(String plorRdpCode, String plorDate, String plorNum);

    /** 발주키로 조회 (납품등록·상태갱신 시) */
    Optional<HottracksPurchaseOrder> findByPlorRdpCodeAndPlorDateAndPlorNum(String plorRdpCode, String plorDate, String plorNum);

    /** 아직 다이제스트로 알리지 않은 발주 (감지 오래된 순) */
    List<HottracksPurchaseOrder> findByNotifiedAtIsNullOrderByDetectedAtAsc();
}
