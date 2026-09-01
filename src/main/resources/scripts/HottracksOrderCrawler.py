"""
교보(핫트랙스 Partner Portal) 발주 수집 크롤러.
로그인 → 홈(mainFinder.do) gridPlor 발주 목록 파싱 → 각 발주의 납품확인 페이지(prdt1012Finder.do)
상세(상품·바코드·발주량) 파싱 → stdout JSON 배열 반환.

로그인/alert 처리는 HottracksCrawler.py 방식을 그대로 따른다.
읽기 전용: 어떤 상태변경(발주확정/납품확인) 버튼도 클릭하지 않는다.

사용법: python HottracksOrderCrawler.py <login_url> <home_url> <username> <password>
stdout: [{"plorRdpCode","plorDate","plorNum","plorRdpName","vndrCode",
           "plorPrgsCdtnCode","plorPrgsCdtnName","sumPlorQntt","plorDecrId",
           "items":[{"barcode","cmdtId","productName","plorQntt","saleUnpr"}]}]
로그는 전부 stderr.
"""
import sys, json, time, re, os, glob, mimetypes, uuid
from urllib import request as urlrequest
from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.chrome.service import Service
from selenium.common.exceptions import TimeoutException, NoSuchElementException
from webdriver_manager.chrome import ChromeDriverManager
from bs4 import BeautifulSoup


def log(msg):
    print(f"DEBUG: {msg}", file=sys.stderr)


def err(msg):
    print(f"ERROR: {msg}", file=sys.stderr)


def make_driver(download_dir=None):
    options = webdriver.ChromeOptions()
    options.add_argument('--headless')
    options.add_argument('--no-sandbox')
    options.add_argument('--disable-dev-shm-usage')
    options.add_argument('--disable-popup-blocking')
    options.add_argument('--disable-gpu')
    options.add_argument('--disable-extensions')
    options.add_argument('--window-size=1400,1000')
    if download_dir:
        # headless Chrome은 다운로드 경로를 명시해야 파일이 저장된다.
        os.makedirs(download_dir, exist_ok=True)
        prefs = {
            "download.default_directory": download_dir,   # storage_dir/tmp
            "download.prompt_for_download": False,
            "download.directory_upgrade": True,
            "safebrowsing.enabled": True,
        }
        options.add_experimental_option("prefs", prefs)
    try:
        service = Service(ChromeDriverManager().install())
    except Exception as e:
        err(f"ChromeDriverManager 오류: {e}")
        p = os.path.join(os.getcwd(), "chromedriver")
        service = Service(p) if os.path.exists(p) else Service(ChromeDriverManager().install())
    driver = webdriver.Chrome(service=service, options=options)
    if download_dir:
        # headless에서 다운로드 허용 (일부 크롬 버전은 prefs만으로 부족)
        try:
            driver.execute_cdp_cmd("Page.setDownloadBehavior",
                                   {"behavior": "allow", "downloadPath": download_dir})
        except Exception as e:
            err(f"setDownloadBehavior 실패(무시): {e}")
    return driver


def accept_alerts(driver, tries=3, wait=2):
    for _ in range(tries):
        try:
            WebDriverWait(driver, wait).until(EC.alert_is_present())
            driver.switch_to.alert.accept()
            log("alert accepted")
        except TimeoutException:
            break


def login(driver, login_url, username, password):
    driver.get(login_url)
    WebDriverWait(driver, 15).until(EC.presence_of_element_located((By.ID, "j_username")))
    driver.find_element(By.ID, "j_username").send_keys(username)
    driver.find_element(By.ID, "j_password").send_keys(password)
    WebDriverWait(driver, 10).until(EC.element_to_be_clickable((By.ID, "loginLink"))).click()
    accept_alerts(driver)
    time.sleep(3)
    accept_alerts(driver)


def parse_grid_orders(html):
    """홈 gridPlor에서 발주 헤더 목록 추출. onclick 함수 인자에서 발주키를 뽑는다."""
    soup = BeautifulSoup(html, "html.parser")
    grid = soup.find("table", id="gridPlor")
    orders = []
    if not grid:
        return orders
    for tr in grid.find_all("tr", class_="jqgrow"):
        row = {}
        # display:none 셀 포함 전체에서 aria-describedby로 컬럼 식별
        for td in tr.find_all("td"):
            key = td.get("aria-describedby", "")
            val = td.get("title", None)
            if val is None:
                val = td.get_text(strip=True)
            val = (val or "").strip()
            if key.endswith("_plorRdpCode"):
                row["plorRdpCode"] = val
            elif key.endswith("_plorDate"):
                row["plorDate"] = val
            elif key.endswith("_plorNum"):
                row["plorNum"] = val
            elif key.endswith("_plorRdpName"):
                row["plorRdpName"] = val
            elif key.endswith("_vndrCode"):
                row["vndrCode"] = val
            elif key.endswith("_vndrName"):
                row["vndrName"] = val
            elif key.endswith("_plorPrgsCdtnCode"):
                row["plorPrgsCdtnCode"] = val
            elif key.endswith("_plorPrgsCdtnName"):
                row["plorPrgsCdtnName"] = val
            elif key.endswith("_sumPlorQntt"):
                row["sumPlorQntt"] = val
            elif key.endswith("_plorDecrId"):
                row["plorDecrId"] = val.strip()
        if row.get("plorRdpCode") and row.get("plorDate") and row.get("plorNum"):
            orders.append(row)
    return orders


def parse_delivery_items(html):
    """납품확인 페이지(prdt1012)에서 상품 행별 바코드·발주량·cmdtId 등 추출."""
    soup = BeautifulSoup(html, "html.parser")
    items = []
    for pros in soup.find_all("input", attrs={"name": "prosQntt"}):
        tr = pros.find_parent("tr")
        if not tr:
            continue
        barcode = ""
        for td in tr.find_all("td"):
            t = td.get_text(strip=True)
            if t.isdigit() and 8 <= len(t) <= 14:
                barcode = t
                break
        def val(name):
            el = tr.find("input", attrs={"name": name})
            return el.get("value", "") if el else ""
        # 상품명: input이 아닌 텍스트 셀 중 한글 포함
        product_name = ""
        for td in tr.find_all("td"):
            if td.find(["input", "select", "textarea"]):
                continue
            t = td.get_text(strip=True)
            if t and re.search(r'[가-힣]', t):
                product_name = t
                break
        items.append({
            "barcode": barcode,
            "cmdtId": val("cmdtId"),
            "productName": product_name,
            "plorQntt": val("plorQntt"),
            "saleUnpr": val("saleUnpr"),
        })
    return items


def fetch_delivery_page(driver, home_url, rdp, date, num):
    """fnOpen2 재현: plorForm에 발주키 세팅 후 새 창으로 POST → 상세 HTML 반환."""
    before = set(driver.window_handles)
    driver.get(home_url)  # plorForm이 있는 홈으로 복귀(상태 안정화)
    accept_alerts(driver)
    driver.execute_script("""
        var frm = document.getElementsByName('plorForm')[0];
        frm.querySelector('[name=plorRdpCode]').value = arguments[0];
        frm.querySelector('[name=plorDate]').value = arguments[1];
        frm.querySelector('[name=plorNum]').value = arguments[2];
        window.open('about:blank','_winPlor','width=1300,height=700,scrollbars=yes,resizable=yes');
        frm.submit();
    """, rdp, date, num)
    time.sleep(3)
    accept_alerts(driver)
    new = [h for h in driver.window_handles if h not in before]
    target = new[-1] if new else driver.window_handles[-1]
    driver.switch_to.window(target)
    time.sleep(1)
    html = driver.page_source
    # 창 정리: 상세 창 닫고 원래 창으로
    try:
        driver.close()
    except Exception:
        pass
    driver.switch_to.window(driver.window_handles[0])
    return html


def _wait_download(download_dir, exts=(".pdf", ".xlsx", ".xls"), timeout=40):
    """download_dir에 exts 파일 생성 & .crdownload 소멸 폴링. 완료 파일 경로 반환(없으면 None)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        # 아직 받는 중이면 대기
        if glob.glob(os.path.join(download_dir, "*.crdownload")):
            time.sleep(0.5)
            continue
        finished = []
        for e in exts:
            finished += glob.glob(os.path.join(download_dir, "*" + e))
        if finished:
            # 가장 최근 파일
            return max(finished, key=os.path.getmtime)
        time.sleep(0.5)
    return None


def upload_to_refrigerator(file_path, endpoint, bucket, sub_path):
    """
    파일을 사내 CDN 서비스(refrigerator)에 multipart POST → CDN URL 반환.
    인증 헤더 없음(실측 확인). 응답 JSON의 .file 이 CDN URL.
    표준 라이브러리만 사용(외부 requests 불필요).
    """
    filename = os.path.basename(file_path)
    with open(file_path, "rb") as f:
        file_bytes = f.read()
    ctype = mimetypes.guess_type(filename)[0] or "application/octet-stream"
    boundary = "----htbnd" + uuid.uuid4().hex

    def field(name, value):
        return (
            f'--{boundary}\r\n'
            f'Content-Disposition: form-data; name="{name}"\r\n\r\n'
            f'{value}\r\n'
        ).encode("utf-8")

    body = bytearray()
    body += field("bucket", bucket)
    body += field("path", sub_path)
    body += (
        f'--{boundary}\r\n'
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f'Content-Type: {ctype}\r\n\r\n'
    ).encode("utf-8")
    body += file_bytes
    body += f'\r\n--{boundary}--\r\n'.encode("utf-8")

    req = urlrequest.Request(
        endpoint, data=bytes(body),
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST")
    with urlrequest.urlopen(req, timeout=60) as resp:
        raw = resp.read().decode("utf-8", "replace")
    data = json.loads(raw)
    url = data.get("file")
    if not url:
        raise RuntimeError(f"refrigerator 응답에 file 없음: {raw[:200]}")
    return url


def download_order_pdf(driver, home_url, download_dir, od, fmt="pdf"):
    """
    발주 [엑셀출력] 원본을 파일로 내려받는다.

    실측 확정(2026-08-28): [엑셀출력](fnPrintByBrowserType) → fnAiView("prdt1010", param)가
    https://admin.hottracks.co.kr/AISERVER?... 뷰어(AIReport 6.5)를 새 창(_ozview)으로 연다.
    뷰어 자체는 파일이 아니라 HTML. 뷰어 안의 [변환후 다운로드](handleConvert())가
    convertType(select) 값(pdf/excel/hwp/word/ppt)에 따라 PDFConvert()/ExcelConvert() 등을
    호출해 실제 파일을 내려준다. 여기선 fmt(기본 pdf)로 받아 레이아웃을 100% 보존한다.

    반환: 다운로드된 로컬 파일 경로(문자열), 실패 시 None.
    """
    rdp, date, num = od["plorRdpCode"], od["plorDate"], od["plorNum"]

    # tmp 비우기(이전 잔여 파일이 최근 파일로 잡히지 않도록)
    os.makedirs(download_dir, exist_ok=True)
    for f in glob.glob(os.path.join(download_dir, "*")):
        try:
            os.remove(f)
        except OSError:
            pass

    driver.get(home_url)
    accept_alerts(driver)
    before_wins = set(driver.window_handles)

    # [엑셀출력] 함수 직접 호출 → AISERVER 뷰어가 새 창(_ozview)으로 열림.
    driver.execute_script(
        "if (typeof fnPrintByBrowserType === 'function') {"
        "  fnPrintByBrowserType(arguments[0], arguments[1], arguments[2], arguments[3],"
        "                       arguments[4], arguments[5], arguments[6], arguments[7]);"
        "} else { throw 'fnPrintByBrowserType 미정의 — 홈 화면에서 호출해야 함'; }",
        od.get("vndrCode", ""), od.get("vndrName", ""), rdp, date, num,
        od.get("plorRdpName", ""), od.get("plorPrgsCdtnCode", ""), od.get("plorDecrId", ""))
    time.sleep(2)
    accept_alerts(driver)

    # 뷰어 창으로 전환
    new_wins = [h for h in driver.window_handles if h not in before_wins]
    viewer = new_wins[-1] if new_wins else driver.window_handles[-1]
    driver.switch_to.window(viewer)
    # 뷰어(AIReport) 렌더 대기 — handleConvert/convertType이 준비될 때까지
    ready = False
    for _ in range(20):
        try:
            ok = driver.execute_script(
                "return (typeof handleConvert==='function') && !!document.getElementById('convertType');")
            if ok:
                ready = True
                break
        except Exception:
            pass
        time.sleep(0.5)
    if not ready:
        err(f"뷰어 준비 안됨(handleConvert/convertType 없음): {rdp}/{date}/{num}")
        _close_extra_windows(driver, before_wins)
        return None

    # convertType=fmt 세팅 후 변환 다운로드
    driver.execute_script(
        "var s=document.getElementById('convertType'); if(s){ s.value=arguments[0]; }"
        "handleConvert();", fmt)
    time.sleep(2)
    accept_alerts(driver)

    exts = (".pdf",) if fmt == "pdf" else (".xlsx", ".xls")
    got = _wait_download(download_dir, exts=exts, timeout=40)
    _close_extra_windows(driver, before_wins)
    if not got:
        err(f"원본 다운로드 대기 시간 초과: {rdp}/{date}/{num}")
        return None
    log(f"원본 다운로드: {got}")
    return got


def _close_extra_windows(driver, keep_wins):
    """keep_wins에 없는 창(뷰어·팝업)을 닫고 원래 창으로 복귀."""
    for h in list(driver.window_handles):
        if h not in keep_wins:
            try:
                driver.switch_to.window(h)
                driver.close()
            except Exception:
                pass
    # 남은 창 중 하나로 복귀
    remaining = [h for h in driver.window_handles if h in keep_wins] or driver.window_handles
    if remaining:
        driver.switch_to.window(remaining[0])


def main():
    if len(sys.argv) < 5:
        print(json.dumps({"error": "usage: <login_url> <home_url> <username> <password> "
                                    "[refrigerator_endpoint] [refrigerator_bucket] [refrigerator_path]"}))
        sys.exit(1)
    login_url, home_url, username, password = sys.argv[1:5]
    # 원본 PDF를 사내 CDN(refrigerator)에 올릴 설정. endpoint 비면 원본 다운로드 스킵.
    fridge_endpoint = sys.argv[5] if len(sys.argv) >= 6 and sys.argv[5].strip() else None
    fridge_bucket   = sys.argv[6] if len(sys.argv) >= 7 and sys.argv[6].strip() else "withcookie-bucket"
    fridge_path     = sys.argv[7] if len(sys.argv) >= 8 and sys.argv[7].strip() else "hottracks-order"

    # 다운로드는 항상 임시 폴더로만 받고, refrigerator 업로드 후 즉시 삭제(서버 디스크에 안 쌓음).
    tmp_dir = os.path.join(os.path.abspath(os.sep + "tmp"), "ht_order_dl")
    driver = make_driver(download_dir=tmp_dir if fridge_endpoint else None)
    try:
        login(driver, login_url, username, password)
        driver.get(home_url)
        accept_alerts(driver)
        WebDriverWait(driver, 15).until(EC.presence_of_element_located((By.ID, "gridPlor")))
        time.sleep(1)
        orders = parse_grid_orders(driver.page_source)
        log(f"발주 {len(orders)}건 감지")

        result = []
        for od in orders:
            try:
                html = fetch_delivery_page(driver, home_url, od["plorRdpCode"], od["plorDate"], od["plorNum"])
                od["items"] = parse_delivery_items(html)
                log(f"발주 {od['plorNum']}: 상품 {len(od['items'])}개")
            except Exception as e:
                err(f"발주 {od.get('plorNum')} 상세 파싱 실패: {e}")
                od["items"] = []

            # 원본 PDF 다운로드 → refrigerator 업로드 → CDN URL (부가 기능, 실패해도 수집 계속)
            od["excelUrl"] = None
            if fridge_endpoint:
                local = None
                try:
                    local = download_order_pdf(driver, home_url, tmp_dir, od, fmt="pdf")
                    if local:
                        sub = f"{fridge_path}/{od['plorRdpCode']}/{od['plorDate']}"
                        od["excelUrl"] = upload_to_refrigerator(local, fridge_endpoint, fridge_bucket, sub)
                        log(f"발주 {od['plorNum']} CDN 저장: {od['excelUrl']}")
                except Exception as e:
                    err(f"발주 {od.get('plorNum')} 원본 저장 실패: {e}")
                finally:
                    if local and os.path.exists(local):
                        try:
                            os.remove(local)
                        except OSError:
                            pass

            result.append(od)

        print(json.dumps(result, ensure_ascii=False))
        sys.stdout.flush()
    except Exception as e:
        err(f"발주 크롤링 실패: {e}")
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        sys.stdout.flush()
    finally:
        driver.quit()


if __name__ == "__main__":
    main()
