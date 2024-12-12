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
            print("error1")
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
        location_options = ["수유점", "건대스타시티점", "합정점", "송도점"]  # 지점명 리스트

        for location in location_options:
            try:
                select = Select(WebDriverWait(driver, 10).until(
                    EC.presence_of_element_located((By.ID, "srchRdpCode"))
                ))

                select.select_by_visible_text(location)

                driver.find_element(By.ID, "btnSearchTrigger").click()

                WebDriverWait(driver, 10).until(EC.presence_of_element_located((By.CLASS_NAME, "table")))
                html_source = driver.page_source

                result = parse_table(html_source)
                location_data.append({"location": location, "data": result})
            except TimeoutException:
                print(f"Table not found for branch: {location}")
            except NoSuchElementException as e:
                print(f"Error interacting with branch: {location}")
            except Exception as e:
                print(f"Unexpected error: {e}")

        return location_data  # 모든 지점 데이터를 반환

    except Exception as e:
        return {"error": str(e)}
    finally:
        driver.quit()

if __name__ == "__main__":
#     activate_and_run()
    import sys

    if len(sys.argv) != 7:
        print(json.dumps({
            "error": "Invalid arguments. Usage: python script.py <login_url> <target_url> <username> <password> <start_date> <end_date>"}))
        sys.exit(1)

    login_url, target_url, username, password, start_date, end_date = sys.argv[1:7]

    result = selenium_login_and_scrape_by_branch(login_url, target_url, username, password, start_date, end_date)

    print(json.dumps(result, ensure_ascii=False))