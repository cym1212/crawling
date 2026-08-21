package codeRecipe.crawling.crawling.domain;

public enum RestockStatus {
    SUGGESTED,   // 제안 생성됨 (미처리)
    REQUESTED,   // 입고 신청 진행됨
    COMPLETED,   // 입고 완료 확인
    SKIPPED      // 관리자가 건너뜀
}
