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
import sys, json, time, re, os, glob, shutil
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


def _wait_download(download_dir, timeout=30):
    """download_dir에 새 .xlsx/.xls 생성 & .crdownload 소멸 폴링. 완료 파일 경로 반환(없으면 None)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        # 아직 받는 중이면 대기
        if glob.glob(os.path.join(download_dir, "*.crdownload")):
            time.sleep(0.5)
            continue
        finished = glob.glob(os.path.join(download_dir, "*.xlsx")) + glob.glob(os.path.join(download_dir, "*.xls"))
        if finished:
            # 가장 최근 파일
            return max(finished, key=os.path.getmtime)
        time.sleep(0.5)
    return None


def download_order_excel(driver, home_url, od, storage_dir):
    """
    발주 [엑셀출력] 원본 엑셀을 내려받아 {storage_dir}/{rdp}_{date}_{num}.xlsx 로 보관.
    반환: 최종 절대경로(문자열), 실패 시 None.

    ⚠️ [엑셀출력] 트리거 셀렉터는 실사이트 확인 필요(의뢰서 §6).
       gridPlor 각 행 버튼: fnPrintByBrowserType(vndrCode, vndrName, rdpCode, plorDate, plorNum,
                                               rdpName, prgsCode, decrId)  ← 세션 조사에서 확인한 시그니처.
       DOM 클릭보다 이 onclick JS 함수를 execute_script로 직접 호출하는 방식이 안정적
       (fetch_delivery_page의 fnOpen2 재현과 동일 요령). 실화면에서 함수명/인자순서가 다르면
       아래 호출부만 맞추면 된다. 버튼 위치(홈 상단/납품확인 탭)도 실화면에서 확인해 주석 갱신할 것.
    """
    rdp, date, num = od["plorRdpCode"], od["plorDate"], od["plorNum"]
    final_path = os.path.join(storage_dir, f"{rdp}_{date}_{num}.xlsx")
    # 이미 있으면 스킵 (파일 시스템이 상태 저장소 — 매시 중복 다운로드 방지)
    if os.path.exists(final_path):
        log(f"엑셀 이미 존재, 스킵: {final_path}")
        return os.path.abspath(final_path)

    download_dir = os.path.join(storage_dir, "tmp")
    os.makedirs(download_dir, exist_ok=True)
    # tmp 비우기(이전 잔여 파일이 최근 파일로 잡히지 않도록)
    for f in glob.glob(os.path.join(download_dir, "*")):
        try:
            os.remove(f)
        except OSError:
            pass

    driver.get(home_url)
    accept_alerts(driver)
    # [엑셀출력] 함수 직접 호출 — 발주 헤더 값으로 인자 구성.
    # vndrName/rdpName은 리포트 표시용이라 파일 내용에 영향 적음(빈 값 허용). 실패 시 예외 → 상위에서 catch.
    driver.execute_script(
        "if (typeof fnPrintByBrowserType === 'function') {"
        "  fnPrintByBrowserType(arguments[0], arguments[1], arguments[2], arguments[3],"
        "                       arguments[4], arguments[5], arguments[6], arguments[7]);"
        "} else { throw 'fnPrintByBrowserType 미정의 — 셀렉터 확인 필요(의뢰서 §6)'; }",
        od.get("vndrCode", ""), od.get("vndrName", ""), rdp, date, num,
        od.get("plorRdpName", ""), od.get("plorPrgsCdtnCode", ""), od.get("plorDecrId", ""))
    time.sleep(2)
    accept_alerts(driver)  # 엑셀출력 확인 alert 있으면 수락

    got = _wait_download(download_dir, timeout=30)
    if not got:
        err(f"엑셀 다운로드 대기 시간 초과: {rdp}/{date}/{num}")
        return None
    # 실제 확장자 유지(.xls면 .xls로) — 우선 규칙대로 .xlsx 경로에 이동하되 실제 확장자 다르면 맞춤
    ext = os.path.splitext(got)[1].lower()
    if ext == ".xls":
        final_path = os.path.join(storage_dir, f"{rdp}_{date}_{num}.xls")
    shutil.move(got, final_path)
    log(f"엑셀 저장: {final_path}")
    return os.path.abspath(final_path)


def main():
    if len(sys.argv) < 5:
        print(json.dumps({"error": "usage: <login_url> <home_url> <username> <password> [storage_dir]"}))
        sys.exit(1)
    login_url, home_url, username, password = sys.argv[1:5]
    storage_dir = sys.argv[5] if len(sys.argv) >= 6 and sys.argv[5].strip() else None

    driver = make_driver(download_dir=os.path.join(storage_dir, "tmp") if storage_dir else None)
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

            # 원본 엑셀 다운로드(부가 기능) — 실패해도 발주 수집은 계속
            od["excelFile"] = None
            if storage_dir:
                try:
                    od["excelFile"] = download_order_excel(driver, home_url, od, storage_dir)
                except Exception as e:
                    err(f"발주 {od.get('plorNum')} 엑셀 다운로드 실패: {e}")

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
