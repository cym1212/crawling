from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.common.keys import Keys
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.chrome.service import Service
from selenium.common.exceptions import TimeoutException, NoSuchElementException, ElementNotInteractableException
from webdriver_manager.chrome import ChromeDriverManager

import os
import sys
import subprocess
import json
import time
import logging

# 디버깅용 로그 출력 함수 - 모든 로그 메시지는 stderr로 출력
def log_debug(message):
    # 로그 메시지를 stderr로 출력 (Java에서는 에러 로그로 취급)
    print(f"DEBUG: {message}", file=sys.stderr)

def log_error(message):
    # 오류 메시지를 stderr로 출력
    print(f"ERROR: {message}", file=sys.stderr)

VENV_PATH = "/venv/bin/activate"

# 가상환경 활성화 및 코드 실행
def activate_and_run():
    if not os.path.exists(VENV_PATH):
        print(f"Error: 가상환경이 존재하지 않습니다: {VENV_PATH}")
        sys.exit(1)

    try:
        # 가상환경 활성화 및 Python 실행
        subprocess.run(f"source {VENV_PATH} && python {' '.join(sys.argv)}", shell=True, check=True)
    except subprocess.CalledProcessError as e:
        print(f"Error during execution: {e}")
        sys.exit(1)

# Selenium으로 로그인 및 데이터 조회
def selenium_login_and_scrape(login_url, username, password, start_date, end_date):
    options = webdriver.ChromeOptions()
#     options.add_argument('--headless')
    options.add_argument('--no-sandbox')
    options.add_argument('--disable-dev-shm-usage')
    options.add_argument('--disable-popup-blocking')
    options.add_argument('--disable-gpu')
    options.add_argument('--disable-extensions')

    try:
        # ChromeDriverManager 설정 - 캐시 디렉토리를 지정하지 않고 기본값 사용
        service = Service(ChromeDriverManager().install())
    except Exception as e:
        log_error(f"ChromeDriverManager 오류: {str(e)}")
        # 대체 방법으로 직접 경로 지정 시도
        chrome_driver_path = os.path.join(os.getcwd(), "chromedriver")
        if os.path.exists(chrome_driver_path):
            service = Service(chrome_driver_path)
        else:
            # 마지막 대안으로 기본 ChromeDriverManager 시도
            service = Service(ChromeDriverManager().install())

    driver = webdriver.Chrome(service=service, options=options)

    try:
        # 1. 로그인 페이지 접속
        log_debug(f"로그인 페이지 접속: {login_url}")
        driver.get(login_url)
        time.sleep(2)

        # 2. 로그인 단계
        try:
            log_debug("로그인 시도...")
            WebDriverWait(driver, 10).until(
                EC.presence_of_element_located((By.ID, "AccountID"))
            )

            driver.find_element(By.ID, "AccountID").send_keys(username)
            driver.find_element(By.ID, "Password").send_keys(password)
            driver.find_element(By.ID, "btnLogin").click()

            log_debug("로그인 완료")
            time.sleep(2)

        except NoSuchElementException as e:
            log_error(f"로그인 요소를 찾을 수 없습니다: {str(e)}")
            return {"error": "Login elements not found"}

        # 3. 팝업 닫기
        try:
            log_debug("팝업 닫기 시도...")
            popup_close = WebDriverWait(driver, 5).until(
                EC.element_to_be_clickable((By.CLASS_NAME, "bntClose"))
            )
            popup_close.click()
            log_debug("팝업 닫기 완료")
            time.sleep(1)
        except TimeoutException:
            log_debug("팝업이 없거나 이미 닫혔습니다.")

        # 4. 판매내역 페이지로 이동
        log_debug("판매내역 페이지로 이동...")
        sales_history_url = "https://ypscm.ypbooks.co.kr/SaleHistory/Index"
        driver.get(sales_history_url)
        time.sleep(2)

        # 5. "문구/음반 판매내역" 탭 클릭 (3번째 탭, 인덱스 2)
        try:
            log_debug("문구/음반 판매내역 탭 클릭...")
            # Kendo TabStrip의 탭들을 찾기
            tabs = WebDriverWait(driver, 10).until(
                EC.presence_of_all_elements_located((By.CSS_SELECTOR, "#tabstrip .k-link"))
            )

            if len(tabs) > 2:
                tabs[2].click()  # 3번째 탭 클릭
                log_debug("탭 클릭 완료")
                time.sleep(2)
            else:
                log_error(f"탭이 충분하지 않습니다. 발견된 탭: {len(tabs)}개")
                return {"error": "Tab not found"}

        except Exception as e:
            log_error(f"탭 클릭 실패: {str(e)}")
            return {"error": f"Tab click failed: {str(e)}"}

        # 6. 날짜 설정 (Kendo DatePicker 사용)
        try:
            log_debug(f"날짜 설정: {start_date} ~ {end_date}")

            # FromDate 설정
            driver.execute_script(f"""
                var fromDatePicker = $('#FromDate').data('kendoDatePicker');
                if (fromDatePicker) {{
                    fromDatePicker.value('{start_date}');
                }}
            """)

            # ToDate 설정
            driver.execute_script(f"""
                var toDatePicker = $('#ToDate').data('kendoDatePicker');
                if (toDatePicker) {{
                    toDatePicker.value('{end_date}');
                }}
            """)

            log_debug("날짜 설정 완료")
            time.sleep(1)

        except Exception as e:
            log_error(f"날짜 설정 실패: {str(e)}")
            return {"error": f"Date setting failed: {str(e)}"}

        # 7. 검색 버튼 클릭
        try:
            log_debug("검색 버튼 클릭...")
            search_button = WebDriverWait(driver, 10).until(
                EC.element_to_be_clickable((By.ID, "btnFetchToyData"))
            )
            search_button.click()
            log_debug("검색 버튼 클릭 완료")
            time.sleep(3)  # 데이터 로딩 대기

        except Exception as e:
            log_error(f"검색 버튼 클릭 실패: {str(e)}")
            return {"error": f"Search button click failed: {str(e)}"}

        # 8. 데이터 추출 (rMateGrid에서)
        try:
            log_debug("데이터 추출 시도...")

            # 방법 1: gridData 전역 변수에서 직접 가져오기 (가장 간단)
            grid_data = driver.execute_script("""
                if (typeof gridData !== 'undefined') {
                    return gridData;
                }
                return null;
            """)

            # 방법 1이 실패하면 방법 2: rMateGrid API 사용
            if grid_data is None:
                log_debug("gridData 변수가 없습니다. rMateGrid API 시도...")
                grid_data = driver.execute_script("""
                    try {
                        var grid = document.getElementById('toygrid');
                        if (grid && grid.getRoot) {
                            var root = grid.getRoot();
                            if (root && root.getDataProvider) {
                                var dataProvider = root.getDataProvider();
                                if (dataProvider && dataProvider.source) {
                                    return dataProvider.source;
                                }
                            }
                        }
                    } catch (e) {
                        console.error('Error accessing grid:', e);
                    }
                    return null;
                """)

            if grid_data is None or len(grid_data) == 0:
                log_error("데이터를 추출할 수 없습니다.")
                return {"error": "No data found in grid"}

            log_debug(f"데이터 추출 완료: {len(grid_data)}개의 레코드")

            # 9. 지점 목록 추출 (WNAME에서 중복 제거)
            locations = []
            location_set = set()

            for record in grid_data:
                location_name = record.get("WNAME", "").strip()
                if location_name and location_name not in location_set:
                    locations.append(location_name)
                    location_set.add(location_name)

            log_debug(f"지점 목록 추출 완료: {len(locations)}개의 지점")

            # 10. 결과 반환 (Arcnbook 패턴과 동일한 구조)
            return {
                "locations": locations,
                "data": grid_data
            }

        except Exception as e:
            log_error(f"데이터 추출 중 오류: {str(e)}")
            import traceback
            log_error(traceback.format_exc())
            return {"error": f"Data extraction failed: {str(e)}"}

    except Exception as e:
        log_error(f"크롤링 실행 중 예외 발생: {str(e)}")
        import traceback
        log_error(traceback.format_exc())
        return {"error": str(e)}
    finally:
        driver.quit()

if __name__ == "__main__":
    import sys

    if len(sys.argv) != 5:
        log_error("Invalid arguments. Usage: python YeongpoongCrawler.py <login_url> <username> <password> <date>")
        print(json.dumps({
            "error": "Invalid arguments. Usage: python YeongpoongCrawler.py <login_url> <username> <password> <date>"}))
        sys.exit(1)

    login_url, username, password, date = sys.argv[1:5]

    # start_date와 end_date를 동일하게 설정 (전날 데이터 조회)
    start_date = date
    end_date = date

    try:
        # 명확히 stderr와 stdout을 분리
        # 모든 로그/디버그 메시지는 stderr로 출력
        # JSON 결과만 stdout으로 출력
        result = selenium_login_and_scrape(login_url, username, password, start_date, end_date)
        # JSON 데이터만 표준 출력으로 출력 (디버그 메시지 없이)
        print(json.dumps(result, ensure_ascii=False))
        sys.stdout.flush()  # stdout 버퍼를 즉시 비움
    except Exception as e:
        # 크롤링 중 예외 발생 시
        log_error(f"크롤링 실행 중 오류 발생: {str(e)}")
        # 에러 JSON도 stdout에 출력 (Java가 파싱할 수 있도록)
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        sys.stdout.flush()  # stdout 버퍼를 즉시 비움
