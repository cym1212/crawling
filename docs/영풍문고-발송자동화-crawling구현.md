# 영풍문고 발송 자동화 — crawling 측 구현 노트

> 영풍문고 발송 업무 자동화에서 **crawling이 담당하는 부분**의 구현 기록.
> 30-we.com 측 작업은 `docs/30we-기능개발-의뢰서.md`의 "의뢰 #3" 참조.

## 배경 / 역할 분담

- 전체 목표: 영풍문고 발송 담당자 수작업(SCM 판매확인 → 엑셀 수기입력 → 명세서·송장 작성 → 발송·문자)의 자동화.
- crawling과 30-we.com은 **같은 bsight RDS를 공유**한다. 영풍 판매는 crawling이 매일 새벽(01~05시, 5회 재시도) 크롤링해 `sales_record`에 적재 중이며, 30-we.com은 이 데이터를 `mysql_bsight` 커넥션으로 읽어 부족재고 감지·명세서·송장·재고차감을 수행한다.
- 따라서 **이 건에서 crawling이 새로 할 일은 데이터 "전송"이 아니라 데이터 "품질 보완"** 뿐이다. crawling→30we 신규 API·전송 계층은 없다(불필요). "수집완료 신호"를 보내는 방식은 다른 서점 크롤링 실패가 영풍 작업을 멈추는 나쁜 결합을 만들어 채택하지 않았다 — 30-we가 어제자 데이터를 그냥 읽으면 된다.

## 핵심: 바코드 정규화 (silent failure 예방)

30-we.com은 bsight `product.product_code`(서점 바코드)를 자사 `TradeProduct` 바코드와 **완전일치(=)** 로 매칭한다. 앞뒤/중간 공백이나 빈 값이 섞이면 매칭이 조용히 실패해 재고 차감이 누락된다(팔렸는데 안 팔린 것으로 처리 → 발주 사고, 에러 없음). 이를 crawling 저장 시점에 예방한다.

### 실측 결과 (2026-08, bsight RDS 읽기전용)

착수 전 실제 데이터를 측정했다.

| 항목 | 결과 |
|---|---|
| bsight `product` 전체 | 214종 |
| 13자리 순수 숫자(EAN-13) | 213종 |
| 앞뒤/중간 공백·비숫자 | 0건 |
| 빈 코드(유령 레코드) | 1건 (product_id=112, 이름·판매도 없음) |
| 영풍 판매 상품 | 102종 전부 13자리 정상 |
| 서점별 규모 | Hottracks 126종/Arcnbook 141/Yeongpoong 102/Libro 165/Hyggebook 126 |

→ **데이터는 이미 양호**하다. 따라서 정규화는 "대규모 정비"가 아니라 **공백 제거 + 빈 값 스킵**의 방어적 수준으로 한정한다. "숫자만 남기기" 같은 공격적 변형은 ISBN 부가기호·비도서 코드를 손상시킬 수 있어 적용하지 않는다.

> ⚠️ 실측으로 확인하지 못한 것: **영풍 바코드 ↔ 30-we `TradeProduct` 바코드 매칭율.** `bsight` 계정은 bsight DB(product/sales_location/sales_record 3개 테이블)만 접근 가능하고 `trade_products`는 30-we 메인 DB에 있다. 이 매칭율은 30-we 환경에서 측정해야 한다(의뢰서 3-0-1 참조). 매칭 실패가 있다면 대부분 "30-we에 해당 바코드 미등록"이 원인일 것이다.

## 구현 내용

### 신규: 공통 정규화 유틸

`src/main/java/codeRecipe/crawling/crawling/util/BarcodeNormalizer.java`

- `normalize(String)`: 모든 공백류(스페이스/탭/개행/NBSP) 제거, 빈 값이면 `null` 반환.
- `isValid(String)`: `normalize` 결과가 null이 아니면 true.
- 순수 함수(Spring/DB 의존 없음) → 단독 단위테스트 가능.

### 5개 서점 executor에 일관 적용

각 `*PythonScriptExecutor`에서 바코드(productCode) 추출 직후 `BarcodeNormalizer.normalize()` 적용 + **빈 값이면 해당 레코드 skip**(경고 로그).

| 서점 | 파일 | 적용 위치 |
|---|---|---|
| 영풍 | `yeongpoong/YeongpoongPythonScriptExecutor.java` | `BAR_CD` → normalize + skip |
| 핫트랙스 | `hottracks/HottracksPythonScriptExecutor.java` | `row.get(2)` → normalize + skip |
| 아크앤북 | `arcnbook/ArcnbookPythonScriptExecutor.java` | `row.get(0)` → normalize + skip |
| 휘게북 | `hyggebook/HyggebookPythonScriptExecutor.java` | `row.get(0)` → normalize + skip |
| 리브로 | `libro/LibroPythonScriptExecutor.java` | `row.get(0)` → normalize + skip |

빈 코드 skip은 실측에서 나온 유령 레코드(product_id=112 같은)의 재발생을 막는다.

### 단위테스트

`src/test/java/codeRecipe/crawling/util/BarcodeNormalizerTest.java` (8 케이스, 전부 통과)

- 정상 EAN-13 유지 / 앞뒤·내부 공백 제거 / NBSP 제거 / null·빈값 → null / 비숫자(하이픈) 보존 / isValid.
- `build.gradle`에 `testImplementation 'org.springframework.boot:spring-boot-starter-test'` 추가(JUnit 5). 기존엔 테스트 의존성이 없었다.
- 실행: `./gradlew test --tests "codeRecipe.crawling.util.BarcodeNormalizerTest"` → `BUILD SUCCESSFUL`, tests=8 failures=0.

## crawling 단독 테스트 (30-we.com 완성 전)

바코드 정규화는 순수 함수라 30-we.com·DB·실제 크롤링 없이 단위테스트로 검증 가능하다(위). 실제 크롤링 저장 경로(executor)는 기존과 동일하게 동작하며, 정규화/skip만 추가됐다.

## 남은 일 / 인수인계

- [ ] (30-we) 의뢰서 "의뢰 #3" 구현 — 부족재고 감지 커맨드, 발송수량 UI, 명세서·송장·재고차감·문자. `docs/30we-기능개발-의뢰서.md` 참조.
- [ ] (30-we) 착수 전 영풍 바코드 ↔ TradeProduct 매칭율 실측(의뢰서 3-0-1).
- [ ] (운영) 영풍 각 지점(위탁 거래처)·상품 임계치(`stock_alert_qty`)와 초기 재고 세팅.
- [ ] (배포 후) 정규화가 기존 크롤링 저장에 영향 없는지 1회 실측 재확인(일치율 개선 여부).
