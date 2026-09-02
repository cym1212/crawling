# crawling 구현 의뢰서 — 교보 발주 원본 엑셀 저장 + 내부 제공 API

> **대상 프로젝트: crawling (이 저장소, Spring Boot)** — 30-we.com 의뢰서가 아닙니다.
> 교보 발주 자동화 확정 플로우(팀 도식 "교보문고(핫트랙스) 발주 자동화 프로세스" / `docs/30we-기능개발-의뢰서.md` 의뢰 #6, 특히 6-7-1 (4))에서 crawling이 맡는 신규 구현 2건입니다.
> 작성일: 2026-08-26

---

## 0. 배경

확정 플로우에서 작업자는 교보 발주 상품을 포장할 때 **핫트랙스 [엑셀출력]로 받은 원본 엑셀을 출력해 동봉**한다. 사람이 핫트랙스에 접속하지 않게 하려면:

1. **crawling 크롤러가 발주 수집 시 엑셀도 다운로드해 보관**하고,
2. **내부 API로 30-we에 제공**한다 (30-we는 이 API를 프록시해 화면에서 다운로드/출력 버튼 제공 — 30-we 쪽 구현은 의뢰 #6에 이미 명세됨).

```
[크롤러, 매시 40분]  발주 수집(기존) + [엑셀출력] 엑셀 다운로드·보관(신규 ①)
[30-we]              발주 화면 [교보 엑셀] 버튼 → crawling 내부 API 호출(신규 ②) → 파일 스트림 → 출력
```

---

## 1. 작업 범위

| # | 작업 | 수정 대상 |
|---|---|---|
| ① | 발주 수집 크롤러에 엑셀 다운로드·보관 추가 | `HottracksOrderCrawler.py`, `HottracksOrderScriptExecutor.java`, `HottracksPurchaseOrder.java`(컬럼 2개), `application-hottracks.yml`(설정 키) |
| ② | 내부 API `GET /internal/hottracks/orders/excel` | `HottracksInternalController.java` |
| 부수 | 다이제스트 크론 12:00 1회 — 코드는 이미 반영됨. **서버 yml 수동 반영 + 배포 확인만** | `application-hottracks.yml` (서버) |

**개발 환경 참고:**
- Java 17 / Spring Boot 3.4, 포트 8081. 크롤러는 Python(Selenium)이며 Java가 `ProcessBuilder`로 `venv/bin/python3`을 실행하고 **stdout JSON**을 파싱한다 (stderr는 로그).
- JPA `ddl-auto: update`라 **엔티티에 컬럼을 추가하면 배포 시 테이블에 자동 반영**된다 (마이그레이션 없음).
- ⚠️ `src/main/resources/application-hottracks.yml`은 자격증명 포함이라 **gitignore** 상태 — 여기에 추가하는 설정 키는 **서버의 실제 파일에 수동 반영**해야 한다.

---

## 2. 작업 ① — 엑셀 다운로드·보관

### 2-1. 설정 추가 (`application-hottracks.yml` — 로컬 + 서버 수동 반영)

```yaml
hottracks:
  excel:
    # 발주 원본 엑셀 보관 디렉터리 (crawling 프로세스 쓰기 권한 필요, 없으면 기동 시 생성)
    storage-dir: "/home/ubuntu/crawling-storage/hottracks-excel"
```

### 2-2. 파일 규칙

- 파일명: `{plorRdpCode}_{plorDate}_{plorNum}.xlsx` — 발주키가 곧 파일 식별자 (예: `527_20260824_00022.xlsx`)
- 같은 발주 재다운로드 시 덮어쓰기. **최종 경로에 파일이 이미 있으면 다운로드 스킵** (파일 시스템이 상태 저장소 — 매시 수집 때 중복 다운로드 방지).

### 2-3. Python — `src/main/resources/scripts/HottracksOrderCrawler.py`

**(a) 사용법 변경** — 6번째 인자(선택) 추가. 미전달 시 엑셀 스킵(기존 동작 그대로):

```
python HottracksOrderCrawler.py <login_url> <home_url> <username> <password> [storage_dir]
```

**(b) `make_driver()`에 다운로드 prefs 추가** (headless Chrome은 명시해야 다운로드됨). `download_dir`은 `{storage_dir}/tmp`를 쓰고 완료 후 최종 파일명으로 이동:

```python
prefs = {
    "download.default_directory": download_dir,      # storage_dir/tmp
    "download.prompt_for_download": False,
    "download.directory_upgrade": True,
    "safebrowsing.enabled": True,
}
options.add_experimental_option("prefs", prefs)
```

**(c) 신규 함수 `download_order_excel(driver, home_url, rdp, date, num, storage_dir)`:**

1. 최종 경로 `{storage_dir}/{rdp}_{date}_{num}.xlsx` 존재 시 **즉시 그 경로 반환(스킵)**.
2. 해당 발주 화면 진입 — 기존 `fetch_delivery_page()`가 `plorForm` 세팅 + JS 실행으로 납품확인 창을 여는 방식을 그대로 참고.
3. **[엑셀출력] 버튼 클릭 → 모달 → 다운로드 확정.**
   - ⚠️ **셀렉터는 실사이트에서 확인 필요** (이 의뢰서에서 확정 못 하는 유일한 부분). 확인 방법: `--headless` 빼고 로컬 실행 → 개발자도구로 버튼의 id/onclick 확인. 기존 코드처럼 **onclick JS 함수를 `driver.execute_script`로 직접 호출**하는 방식을 권장 (DOM 클릭보다 안정적 — `fetch_delivery_page`의 `fnOpen2` 재현과 같은 요령).
   - 버튼 위치가 발주현황(홈) 상단인지, 납품확인 페이지 내 탭인지도 이때 확인해 주석으로 남길 것.
4. 다운로드 완료 대기: `download_dir`에 새 `.xlsx`(또는 `.xls`) 생성 & `.crdownload` 소멸 폴링, 최대 30초.
5. 최종 경로로 `shutil.move` → **절대경로 반환**. 실패 시 `None` 반환.

**(d) `main()` 수정** — 각 발주 처리 루프에서:

```python
od["excelFile"] = None
if storage_dir:
    try:
        od["excelFile"] = download_order_excel(driver, home_url, od["plorRdpCode"], od["plorDate"], od["plorNum"], storage_dir)
    except Exception as e:
        err(f"발주 {od.get('plorNum')} 엑셀 다운로드 실패: {e}")   # 엑셀은 부가 기능 — 실패해도 발주 수집은 계속
```

### 2-4. Java — `hottracks/HottracksOrderScriptExecutor.java`

1. 설정 주입 + 인자 전달:

```java
@Value("${hottracks.excel.storage-dir:}")
private String excelStorageDir;

// collectOrders() — 디렉터리 보장 후 인자 추가 (비어있으면 미전달 = 엑셀 스킵)
List<String> cmd = new ArrayList<>(List.of(pythonPath, tempScript.getAbsolutePath(),
        loginUrl, homeUrl, username, password));
if (excelStorageDir != null && !excelStorageDir.isBlank()) {
    new File(excelStorageDir, "tmp").mkdirs();
    cmd.add(excelStorageDir);
}
ProcessBuilder pb = new ProcessBuilder(cmd);
```

2. `parseAndSave()` — 중복 발주 처리 변경: 현재는 `existsBy...`로 **통째로 skip**하는데, 이미 수집된 발주라도 **엑셀이 이번에 새로 저장됐으면 경로를 기록**해야 한다. `existsBy` 대신 `findBy`로 바꾼다:

```java
String excelFile = text(od, "excelFile");   // null/빈 문자열이면 미저장

var existing = orderRepository.findByPlorRdpCodeAndPlorDateAndPlorNum(plorRdpCode, plorDate, plorNum);
if (existing.isPresent()) {
    HottracksPurchaseOrder ex = existing.get();
    if ((ex.getExcelPath() == null || ex.getExcelPath().isBlank()) && !excelFile.isEmpty()) {
        ex.setExcelPath(excelFile);
        ex.setExcelSavedAt(now);
        orderRepository.save(ex);
        log.info("교보 발주 엑셀 소급 저장: {}/{}/{}", plorRdpCode, plorDate, plorNum);
    } else {
        log.info("이미 수집된 발주 건너뜀: {}/{}/{}", plorRdpCode, plorDate, plorNum);
    }
    continue;
}
```

3. 신규 발주 저장 빌더에 추가: `.excelPath(excelFile.isEmpty() ? null : excelFile)` / `.excelSavedAt(excelFile.isEmpty() ? null : now)`

> 엔티티가 `@Builder`만 있고 setter가 없으면 `excelPath`/`excelSavedAt`에 한해 setter(또는 갱신 메서드)를 추가한다.

### 2-5. 엔티티 — `domain/HottracksPurchaseOrder.java` 컬럼 2개 추가

```java
@Column(name = "excel_path", length = 500)
private String excelPath;              // 발주 원본 엑셀 저장 경로 (null = 미저장)

@Column(name = "excel_saved_at")
private LocalDateTime excelSavedAt;    // 엑셀 저장 시각
```

---

## 3. 작업 ② — 내부 API `GET /internal/hottracks/orders/excel`

30-we 의뢰서 6-7-1 (4)에 이미 공지된 계약 그대로 구현한다:

```
GET {BASE}/internal/hottracks/orders/excel?plorRdpCode=527&plorDate=20260824&plorNum=00022
Header: X-Internal-Token: {토큰}

200: xlsx 파일 스트림 (Content-Disposition: attachment; filename="527_20260824_00022.xlsx")
404: {"error":"order_not_found"}                          — crawling에 없는 발주키
404: {"error":"excel_not_found","message":"..."}          — 발주는 있으나 엑셀 미저장
401: {"error":"unauthorized"}
```

`hottracks/HottracksInternalController.java`에 아래 메서드를 추가한다 (기존 `deliver`와 같은 클래스, `HottracksInternalAuth` 재사용, `HottracksPurchaseOrderRepository` 주입 추가 — `findByPlorRdpCodeAndPlorDateAndPlorNum`은 리포지토리에 이미 있음):

```java
@Operation(summary = "발주 원본 엑셀 다운로드 (30-we [교보 엑셀] 버튼용)",
        description = "발주키의 핫트랙스 [엑셀출력] 원본 엑셀을 반환한다. "
                + "오류: 401 unauthorized / 404 order_not_found·excel_not_found.")
@GetMapping("/orders/excel")
public ResponseEntity<Object> orderExcel(
        @RequestHeader(value = "X-Internal-Token", required = false) String token,
        @RequestParam String plorRdpCode,
        @RequestParam String plorDate,
        @RequestParam String plorNum) {
    if (!internalAuth.isAuthorized(token)) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }
    var orderOpt = orderRepository.findByPlorRdpCodeAndPlorDateAndPlorNum(plorRdpCode, plorDate, plorNum);
    if (orderOpt.isEmpty()) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "order_not_found"));
    }
    String path = orderOpt.get().getExcelPath();
    File file = (path == null || path.isBlank()) ? null : new File(path);
    if (file == null || !file.exists()) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "excel_not_found",
                "message", "엑셀이 아직 저장되지 않았습니다. 다음 수집(매시 40분) 후 다시 시도하세요."));
    }
    String filename = plorRdpCode + "_" + plorDate + "_" + plorNum + ".xlsx";
    return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(new FileSystemResource(file));
}
```

- 필요 import: `org.springframework.core.io.FileSystemResource`, `org.springframework.http.HttpHeaders`, `org.springframework.http.MediaType`, `org.springframework.web.bind.annotation.GetMapping`, `org.springframework.web.bind.annotation.RequestParam`, `java.io.File`.
- **경로 주입 안전**: 요청 파라미터는 DB 조회 키로만 쓰고, 파일 경로는 DB에 저장된 `excelPath`만 사용한다 (path traversal 불가).
- 실제 다운로드 파일이 `.xls`(구형식)로 확인되면 확장자·Content-Type(`application/vnd.ms-excel`)을 실제 파일 기준으로 맞출 것 (Python 저장 규칙 2-2와 함께 일괄 변경).

---

## 4. 부수 작업 — 다이제스트 크론 (코드 반영 완료, 배포만)

`application-hottracks.yml`의 다이제스트 크론이 12:00 1회로 이미 변경돼 있다 (로컬):

```yaml
hottracks:
  schedule:
    order-digest-cron: "0 0 12 * * *"   # 매일 12:00 1회 — 전날 12:00 이후 수집분(미알림 전체)
```

- 이 파일은 gitignore이므로 **서버의 실제 yml에 수동 반영** 필요 (2-1의 `excel.storage-dir`과 함께 한 번에).
- 다이제스트 로직 자체는 "미알림(notified_at null) 발주 전체 발송" 방식이라 **코드 수정 없이 크론 변경만으로 요구 충족**.

---

## 5. 완료 기준

- [ ] 수집 실행(매시 40분 또는 Swagger 수동 실행) 시 **신규 발주의 엑셀**이 `storage-dir`에 `{발주키}.xlsx`로 저장되고 `excel_path`/`excel_saved_at` 기록됨
- [ ] 이미 엑셀이 있는 발주는 재다운로드하지 않음 (파일 존재 스킵) / 기수집·엑셀 미보유 발주는 다음 수집 때 소급 저장됨
- [ ] **엑셀 다운로드 실패가 발주 수집을 막지 않음** (stderr 로그만 남고 수집 정상 완료)
- [ ] 내부 API 4케이스: 정상 200(파일 열림) / 토큰 없음·오류 401 / 없는 발주키 404 `order_not_found` / 엑셀 미저장 404 `excel_not_found`
- [ ] Swagger(`/swagger`)에서 신규 엔드포인트 노출 확인
- [ ] 서버 yml 수동 반영(`excel.storage-dir` + `order-digest-cron`) 후 재시작 → 12:00 다이제스트 1회 발송 확인
- [ ] (30-we 의뢰 #6 구현 이후) 30-we [교보 엑셀] 버튼 → 프록시 다운로드 end-to-end 확인

## 6. 구현 중 실사이트 확인 필요 (셀렉터)

- **[엑셀출력] 버튼의 위치와 셀렉터** — 발주현황(홈 gridPlor) 상단인지, 납품확인 페이지(prdt1012) 내 탭인지 실화면 확인 후 onclick JS 직접 호출 방식으로 구현하고, 확인 결과를 스크립트 주석에 남길 것.
- 모달에서 다운로드가 즉시 시작되는지 / 옵션 선택 후 확인 버튼이 필요한지.
- 계정·URL 등 접속 정보는 `application-hottracks.yml`에 이미 있는 값을 그대로 사용 (별도 전달 불필요).
