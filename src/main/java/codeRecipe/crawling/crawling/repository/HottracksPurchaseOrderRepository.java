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

    /**
     * 미납품(delivered_at null) + 발주일이 plorDateFrom 이상인 발주.
     * "사라짐 감지"용: 홈 조회범위(최근 약 7일) 안의 미처리 발주 중, 이번 홈 목록에 없으면 처리된 것으로 판정.
     * plorDate는 "YYYYMMDD" 문자열이라 문자열 비교로 날짜 하한 필터 가능(같은 자리수라 사전순=날짜순).
     */
    List<HottracksPurchaseOrder> findByDeliveredAtIsNullAndPlorDateGreaterThanEqual(String plorDateFrom);
}
