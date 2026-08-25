"""
교보(핫트랙스) 납품등록 봇.
30-we.com [납품확인] 버튼 → crawling 내부 API 가 이 스크립트를 실행한다.
발주의 납품확인 페이지(prdt1012)를 열어 각 상품 행의 prosQntt(납품량)에 명세서 수량을 입력하고,
mode에 따라 ②임시저장 또는 ③납품확인을 클릭한다.

⚠️ 안전: mode="TMPR"이면 임시저장까지만(③납품확인 절대 클릭 안 함).
        mode="CMPLT"이면 ③납품확인(#saveLink)까지 클릭.
        내부 API가 allow-final-confirm=false면 CMPLT 요청이 와도 TMPR로 강등해 호출한다.

사용법: python HottracksDeliverBot.py <login_url> <home_url> <username> <password>
                  <plorRdpCode> <plorDate> <plorNum> <itemsJson> <mode>
  itemsJson: [{"barcode":"8809...","qty":3}, ...]
stdout: {"ok":bool, "filledRows":int, "unmatched":["바코드",...], "mode":"TMPR|CMPLT", "message":"..."}
로그는 전부 stderr.
"""
import sys, json, time
from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.chrome.service import Service
from selenium.common.exceptions import TimeoutException
from webdriver_manager.chrome import ChromeDriverManager


def log(m):
    print(f"DEBUG: {m}", file=sys.stderr)


def err(m):
    print(f"ERROR: {m}", file=sys.stderr)


def make_driver():
    o = webdriver.ChromeOptions()
    o.add_argument('--headless')
    o.add_argument('--no-sandbox')
    o.add_argument('--disable-dev-shm-usage')
    o.add_argument('--disable-popup-blocking')
    o.add_argument('--disable-gpu')
    o.add_argument('--window-size=1400,1000')
    try:
        service = Service(ChromeDriverManager().install())
    except Exception as e:
        err(f"driver 오류: {e}")
        import os
        p = os.path.join(os.getcwd(), "chromedriver")
        service = Service(p) if os.path.exists(p) else Service(ChromeDriverManager().install())
    return webdriver.Chrome(service=service, options=o)


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


def open_delivery_page(driver, home_url, rdp, date, num):
    """fnOpen2 재현: plorForm 세팅 후 새 창으로 POST → 납품확인 창으로 전환."""
    before = set(driver.window_handles)
    driver.get(home_url)
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
    driver.switch_to.window(new[-1] if new else driver.window_handles[-1])
    time.sleep(1)


def fill_quantities(driver, qty_by_barcode):
    """각 상품 행의 prosQntt에 명세서 수량 입력. 반환: (filled_rows, unmatched_barcodes)."""
    # prosQntt input들이 상품 행마다 존재. 바코드로 행 매칭.
    rows = driver.find_elements(By.XPATH, "//input[@name='prosQntt']")
    log(f"납품확인 상품 행 {len(rows)}개")
    filled = 0
    matched_barcodes = set()
    for pros in rows:
        # 같은 tr 내에서 바코드 텍스트 찾기
        tr = pros.find_element(By.XPATH, "./ancestor::tr[1]")
        tds = tr.find_elements(By.TAG_NAME, "td")
        barcode = ""
        for td in tds:
            t = td.text.strip()
            if t.isdigit() and 8 <= len(t) <= 14:
                barcode = t
                break
        if barcode and barcode in qty_by_barcode:
            qty = qty_by_barcode[barcode]
            driver.execute_script(
                "arguments[0].value = arguments[1];"
                "arguments[0].dispatchEvent(new Event('change', {bubbles:true}));",
                pros, str(qty))
            filled += 1
            matched_barcodes.add(barcode)
            log(f"바코드 {barcode} 납품량 {qty} 입력")
    unmatched = [b for b in qty_by_barcode.keys() if b not in matched_barcodes]
    return filled, unmatched


def main():
    if len(sys.argv) != 10:
        print(json.dumps({"ok": False, "message": "invalid args"}))
        sys.exit(1)
    login_url, home_url, username, password, rdp, date, num, items_json, mode = sys.argv[1:10]
    items = json.loads(items_json)
    qty_by_barcode = {str(it["barcode"]): int(it["qty"]) for it in items}

    driver = make_driver()
    try:
        login(driver, login_url, username, password)
        open_delivery_page(driver, home_url, rdp, date, num)

        filled, unmatched = fill_quantities(driver, qty_by_barcode)
        if filled == 0:
            print(json.dumps({"ok": False, "filledRows": 0, "unmatched": unmatched,
                              "mode": mode, "message": "입력된 상품이 없습니다(바코드 미매칭)"}, ensure_ascii=False))
            return

        if mode == "CMPLT":
            # ③ 납품확인 (#saveLink → fnCallbkCmplt)
            driver.execute_script("document.getElementById('saveLink').click();")
            action = "납품확인(③)"
        else:
            # ② 임시저장 (fnCallbkTmpr). 버튼 텍스트로 탐색.
            driver.execute_script("""
                var els = document.querySelectorAll('a, span, button');
                for (var i=0;i<els.length;i++){
                    if (els[i].textContent.indexOf('임시저장') > -1){ els[i].click(); break; }
                }
            """)
            action = "임시저장(②)"
        time.sleep(2)
        accept_alerts(driver)   # 저장 확인 alert 수락
        time.sleep(1)

        print(json.dumps({"ok": True, "filledRows": filled, "unmatched": unmatched,
                          "mode": mode, "message": action + " 완료"}, ensure_ascii=False))
        sys.stdout.flush()
    except Exception as e:
        err(f"납품등록 실패: {e}")
        print(json.dumps({"ok": False, "message": str(e)}, ensure_ascii=False))
        sys.stdout.flush()
    finally:
        driver.quit()


if __name__ == "__main__":
    main()
