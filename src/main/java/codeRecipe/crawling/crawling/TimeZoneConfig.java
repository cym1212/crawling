//package codeRecipe.crawling.crawling;
//
//import jakarta.annotation.PostConstruct;
//import org.springframework.stereotype.Component;
//
//import java.util.TimeZone;
//
//@Component
//public class TimeZoneConfig {
//    @PostConstruct
//    public void init() {
//        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
//        System.out.println("JVM Time Zone set to: " + TimeZone.getDefault().getID());
//    }
//}