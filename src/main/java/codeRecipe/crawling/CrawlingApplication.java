package codeRecipe.crawling;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@Slf4j
public class CrawlingApplication {

	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
		log.info("JVM Time Zone: {}", TimeZone.getDefault().getID());
		SpringApplication.run(CrawlingApplication.class, args);
	}

}
