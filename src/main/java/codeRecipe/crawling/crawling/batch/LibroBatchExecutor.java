package codeRecipe.crawling.crawling.batch;

import codeRecipe.crawling.crawling.hottracks.HottracksPythonScriptExecutor;
import codeRecipe.crawling.crawling.libro.LibroPythonScriptExecutor;

import java.time.LocalDate;

public class LibroBatchExecutor {
    private final LibroPythonScriptExecutor executor; // Python 실행 클래스

    public LibroBatchExecutor(LibroPythonScriptExecutor executor) {
        this.executor = executor;
    }

    // 날짜 범위를 받아서 실행하는 메서드
    public void executeForDateRange(String startDate, String endDate) throws Exception {
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);

        // 2. 날짜를 하루씩 증가시키면서 실행
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            // 3. Python 실행 클래스에 현재 날짜 설정
            executor.setTargetDate(date);

            // 4. Python 스크립트를 실행
            System.out.println("Executing for date: " + date);
            executor.excutePythonScript();
        }
    }
}
