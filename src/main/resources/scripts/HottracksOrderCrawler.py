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
import sys, json, time, re
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


def make_driver():
    options = webdriver.ChromeOptions()
    options.add_argument('--headless')
    options.add_argument('--no-sandbox')
    options.add_argument('--disable-dev-shm-usage')
    options.add_argument('--disable-popup-blocking')
    options.add_argument('--disable-gpu')
    options.add_argument('--disable-extensions')
    options.add_argument('--window-size=1400,1000')
    try:
        service = Service(ChromeDriverManager().install())
    except Exception as e:
        err(f"ChromeDriverManager 오류: {e}")
        import os
        p = os.path.join(os.getcwd(), "chromedriver")
        service = Service(p) if os.path.exists(p) else Service(ChromeDriverManager().install())
    return webdriver.Chrome(service=service, options=options)


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


def main():
    if len(sys.argv) != 5:
        print(json.dumps({"error": "usage: <login_url> <home_url> <username> <password>"}))
        sys.exit(1)
    login_url, home_url, username, password = sys.argv[1:5]

    driver = make_driver()
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
