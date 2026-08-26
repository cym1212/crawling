package codeRecipe.crawling.crawling.domain;

/** 로켓그로스 입고 계획 상태 (의뢰 #8) */
public enum InboundPlanStatus {
    REQUESTED,           // 신청됨 (에이전트 실행 대기)
    RPA_RUNNING,         // WING 자동 입력 진행 중
    SUBMITTED,           // WING 제출 완료 — 송장 발급 가능
    INVOICE_ISSUED,      // 로젠 송장 발급됨 — WING 등록 대기
    INVOICE_REGISTERED,  // WING 송장 등록 완료
    COMPLETED,           // 입고 반영 확인
    MISMATCH,            // 신청 대비 반영 수량 불일치
    FAILED,              // 자동화 실패 — 수동 처리 필요
    CANCELLED            // 취소됨
}
