import json
from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.chrome.service import Service
from webdriver_manager.chrome import ChromeDriverManager
from selenium.webdriver.common.action_chains import ActionChains
from selenium.webdriver.common.keys import Keys

import sys
import requests
from datetime import datetime
from pyvirtualdisplay import Display
import time
import traceback

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

def capture_s_id_from_request_headers(login_url, username, password):
    #로컬에서 실행시 주석 처리 (서버에서 가상화면 띄우는 코드)
    display = Display(visible=0, size=(1920, 1080))
    display.start()

    options = webdriver.ChromeOptions()
#     options.add_argument('--headless=new')
    options.add_argument('--no-sandbox')
    options.add_argument('--disable-dev-shm-usage')
    options.add_argument('--disable-popup-blocking')


    # ChromeDriver 실행
    service = Service(ChromeDriverManager().install())
    driver = webdriver.Chrome(service=service, options=options)

    try:


        # 로그인 페이지 열기
        driver.get(login_url)

        # 로그인
        driver.execute_script("document.getElementById('O2F_id-inputEl').value = arguments[0];", '리브로')
        time.sleep(1.5)
        driver.execute_script("document.getElementById('O13_id-inputEl').value = arguments[0];", username)
        time.sleep(1.5)
        driver.execute_script("document.getElementById('O17_id-inputEl').value = arguments[0];", password)
        time.sleep(1.5)
#         WebDriverWait(driver, 20).until(EC.presence_of_element_located((By.ID, "O13_id-inputEl"))).send_keys(username)
#         WebDriverWait(driver, 20).until(EC.presence_of_element_located((By.ID, "O17_id-inputEl"))).send_keys(password)
        WebDriverWait(driver, 20).until(EC.element_to_be_clickable((By.ID, "O1B_id-btnInnerEl"))).click()

        time.sleep(2)
        WebDriverWait(driver, 20).until(
            EC.element_to_be_clickable((
                By.XPATH,
                '(//div[contains(@class, "x-tree-elbow-img") and contains(@class, "x-tree-elbow-plus") and contains(@class, "x-tree-expander")])[2]'
            ))
        ).click()

        time.sleep(1)

        WebDriverWait(driver, 20).until(
            EC.element_to_be_clickable((
                By.XPATH,
                '(//div[contains(@class, "x-grid-cell-inner") and contains(@class, "x-grid-cell-inner-treecolumn")])[4]'
            ))
        ).click()

        time.sleep(1)


        calendar_button = WebDriverWait(driver, 20).until(
            EC.element_to_be_clickable((By.ID, "O209_id-triggerWrap"))
        )
        driver.save_screenshot('debug_screenshot_calendar.png')
        calendar_button.click()


        actions = ActionChains(driver)
        actions.send_keys(Keys.ARROW_LEFT)
        actions.send_keys(Keys.ENTER)
        actions.perform()

        time.sleep(1)

        calendar_button = WebDriverWait(driver, 20).until(
            EC.element_to_be_clickable((By.ID, "O20D_id-triggerWrap"))
        )
        driver.save_screenshot('debug_screenshot_calendar.png')
        calendar_button.click()


        actions = ActionChains(driver)
        actions.send_keys(Keys.ARROW_LEFT)
        actions.send_keys(Keys.ENTER)
        actions.perform()



        driver.execute_script("document.getElementById('O1B6_id-inputEl').value = arguments[0];", '판매금액')

        time.sleep(1)

        WebDriverWait(driver, 20).until(
            EC.element_to_be_clickable((
                By.XPATH,
                '(//a[contains(@class, "x-btn") and contains(@class, "x-unselectable") and contains(@class, "x-box-item")and contains(@class, "x-toolbar-item")and contains(@class, "x-btn-default-toolbar-large")])[1]'
            ))
        ).click()


        time.sleep(3)
        table_data = []
        containers = WebDriverWait(driver, 20).until(
            EC.presence_of_all_elements_located((By.CLASS_NAME, "x-grid-item-container"))
        )

        for container_index in [2,3]:
            table_rows = []
            tables = containers[container_index].find_elements(By.TAG_NAME,"table")
            for table in tables:
                rows = table.find_elements(By.TAG_NAME,"tr")
                for row in rows:
                    cells = row.find_elements(By.TAG_NAME,"td")
                    row_data = [cell.text for cell in cells]
                    table_rows.append(row_data)
            table_data.append(table_rows)

        merged_data = []
        table_1 = table_data[0]
        table_2 = table_data[1]


        for i in range(len(table_1)):
            row_1 = table_1[i]
            row_2 = table_2[i]
            merged_data.append(row_1 + row_2)

        return merged_data


    except Exception as e:
        return {
            "error": "An error occurred while capturing data",
            "details": str(e),
            "traceback": traceback.format_exc(),
        }

    finally:
        driver.quit()
#         로컬에서 실행시 주석 처리 (서버에서 가상화면 띄우는 코드)
        display.stop()




if __name__ == "__main__":
#     activate_and_run()

    login_url = sys.argv[1]
    username = sys.argv[2]
    password = sys.argv[3]

    try:
        result = capture_s_id_from_request_headers(login_url, username, password)
        if isinstance(result, dict) and "error" in result:
            # 에러 발생 시 JSON 출력
            print(json.dumps(result, ensure_ascii=False))
            sys.exit(1)
        else:
            # 성공 시 데이터 JSON 출력
            print(json.dumps(result, ensure_ascii=False))
    except Exception as e:
        print(json.dumps({
            "error": "Unexpected error in main execution",
            "details": str(e),
            "traceback": traceback.format_exc()
        }, ensure_ascii=False))
        sys.exit(1)
