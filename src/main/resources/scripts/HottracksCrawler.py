from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.common.keys import Keys
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.chrome.service import Service
from selenium.common.exceptions import TimeoutException, NoSuchElementException, ElementNotInteractableException
from webdriver_manager.chrome import ChromeDriverManager
from selenium.webdriver.support.ui import Select
from bs4 import BeautifulSoup

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

# HTML에서 테이블 데이터를 추출
def parse_table(html_source):
    try:
        soup = BeautifulSoup(html_source, 'html.parser')
        table = soup.find('table', class_='table')

        if not table:
            print("error1", file=sys.stderr)
            return {"error": "Table not found"}

        headers = [th.text.strip() for th in table.find('thead').find_all('th')]

        rows = []
        for tr in table.find('tbody').find_all('tr'):
            cells = [td.text.strip() for td in tr.find_all('td')]
            rows.append(cells)

        return {"headers": headers, "rows": rows}

    except Exception as e:
        return {"error": str(e)}

# Selenium으로 로그인 및 데이터 조회
def selenium_login_and_scrape_by_branch(login_url, target_url, username, password, start_date, end_date):
    options = webdriver.ChromeOptions()
    options.add_argument('--headless')
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
        driver.get(login_url)

        # 로그인 단계
        try:
            driver.find_element(By.ID, "j_username").send_keys(username)
            driver.find_element(By.ID, "j_password").send_keys(password)
            driver.find_element(By.ID, "j_password").send_keys(Keys.RETURN)
        except NoSuchElementException as e:
            return {"error": "Login elements not found\n"}

        # Alert 처리
        try:
            WebDriverWait(driver, 10).until(EC.alert_is_present())
            alert = driver.switch_to.alert
            alert.accept()
        except TimeoutException:
            print("No alert present.")

        driver.get(target_url)

        driver.execute_script("document.getElementById('srchDateF').value = arguments[0];", start_date)
        driver.execute_script("document.getElementById('srchDateT').value = arguments[0];", end_date)

        location_data = []
        
        # 수불처 목록을 동적으로 가져오기
        try:
            select_element = WebDriverWait(driver, 10).until(
                EC.presence_of_element_located((By.ID, "srchRdpCode"))
            )
            
            # select 요소에서 모든 option 가져오기
            options = select_element.find_elements(By.TAG_NAME, "option")
            
            # 텍스트 값 추출 (첫 번째 "전체" 옵션은 제외)
            location_options = []
            for option in options[1:]:  # 첫 번째 "전체" 옵션은 스킵
                option_text = option.text.strip()
                option_value = option.get_attribute("value")
                
                # "(종료)"가 포함된 매장은 제외 (선택적)
                if "(종료)" in option_text:
                    log_debug(f"종료된 매장 제외: {option_text}")
                    continue
                    
                location_options.append(option_text)
                log_debug(f"발견된 수불처: {option_text} (value: {option_value})")
            
            log_debug(f"총 {len(location_options)}개의 수불처를 발견했습니다.")
            
        except Exception as e:
            log_error(f"수불처 목록을 가져오는 중 오류 발생: {str(e)}")
            # 실패 시 기본 하드코딩 목록 사용
            location_options = [
                "물류", "광화문점", "이화여대점", "강남점", "서울대점", "잠실점", "건대스타시티점", "창원점",
                "거제디큐브", "목동점", "천안점", "온라인몰", "영등포점", "대구점","원그로브점", "수유점", "부산점",
                "디큐브시티점", "판교점", "전주점", "동대문점", "울산점", "일산점", "송도점", "대전점",
                "광교월드스퀘어센터", "센텀시티점", "해운대 팝업스토어", "전북대점", "인천점",
                "부천점", "은평점", "칠곡점", "세종점","신채널3", "청량리점", "합정점", "가든파이브", "평촌점",
                "경성대.부경대센터", "분당점", "광주상무센터", "광교점", "천호점", "카페자우랩",
                "B2B영업팀", "파주본점", "B2B개발팀(핫트마켓)", "B2B영업팀_B", "본사문구음반",
                "해외영업", "보관지원센터", "거제디큐브", "대백프라자"
            ]

        for location in location_options:
            try:
                # 매번 페이지를 새로고침하여 요소 참조가 유효한지 확인
                driver.get(target_url)
                
                # 날짜 설정 - 자바스크립트로 직접 값 설정
                driver.execute_script("document.getElementById('srchDateF').value = arguments[0];", start_date)
                driver.execute_script("document.getElementById('srchDateT').value = arguments[0];", end_date)
                
                # 매번 새로운 select 요소를 찾음
                WebDriverWait(driver, 10).until(
                    EC.presence_of_element_located((By.ID, "srchRdpCode"))
                )
                
                # select 요소를 JavaScript로 직접 설정하여 stale element 문제 방지
                location_select_script = f"""
                    var select = document.getElementById('srchRdpCode');
                    for(var i=0; i<select.options.length; i++) {{
                        if(select.options[i].text === '{location}') {{
                            select.selectedIndex = i;
                            break;
                        }}
                    }}
                """
                driver.execute_script(location_select_script)
                
                # 검색 버튼 클릭
                search_button = WebDriverWait(driver, 10).until(
                    EC.element_to_be_clickable((By.ID, "btnSearchTrigger"))
                )
                search_button.click()
                
                # 테이블이 로드될 때까지 명시적으로 대기
                WebDriverWait(driver, 10).until(
                    EC.presence_of_element_located((By.CLASS_NAME, "table"))
                )
                
                # 페이지가 완전히 로드될 때까지 잠시 대기
                time.sleep(1)  # 필요에 따라 조정
                
                html_source = driver.page_source
                result = parse_table(html_source)
                location_data.append({"location": location, "data": result})
                
                log_debug(f"성공적으로 {location} 데이터를 크롤링했습니다.")
                
            except TimeoutException:
                log_error(f"Table not found for branch: {location}")
            except NoSuchElementException as e:
                log_error(f"Error finding element for branch: {location} - {str(e)}")
            except Exception as e:
                log_error(f"Unexpected error for {location}: {str(e)}")

        return location_data  # 모든 지점 데이터를 반환

    except Exception as e:
        return {"error": str(e)}
    finally:
        driver.quit()

if __name__ == "__main__":
#     activate_and_run()
    import sys

    if len(sys.argv) != 7:
        log_error("Invalid arguments. Usage: python script.py <login_url> <target_url> <username> <password> <start_date> <end_date>")
        print(json.dumps({
            "error": "Invalid arguments. Usage: python script.py <login_url> <target_url> <username> <password> <start_date> <end_date>"}))
        sys.exit(1)

    login_url, target_url, username, password, start_date, end_date = sys.argv[1:7]

    try:
        # 명확히 stderr와 stdout을 분리
        # 모든 로그/디버그 메시지는 stderr로 출력
        # JSON 결과만 stdout으로 출력
        result = selenium_login_and_scrape_by_branch(login_url, target_url, username, password, start_date, end_date)
        # JSON 데이터만 표준 출력으로 출력 (디버그 메시지 없이)
        print(json.dumps(result, ensure_ascii=False))
        sys.stdout.flush()  # stdout 버퍼를 즉시 비움
    except Exception as e:
        # 크롤링 중 예외 발생 시
        log_error(f"크롤링 실행 중 오류 발생: {str(e)}")
        # 에러 JSON도 stdout에 출력 (Java가 파싱할 수 있도록)
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        sys.stdout.flush()  # stdout 버퍼를 즉시 비움