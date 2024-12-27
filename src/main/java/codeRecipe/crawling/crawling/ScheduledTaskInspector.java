//package codeRecipe.crawling.crawling;
//
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.ApplicationArguments;
//import org.springframework.boot.ApplicationRunner;
//import org.springframework.stereotype.Component;
//
//import java.util.List;
//
//@Component
//public class ScheduledTaskInspector implements ApplicationRunner {
//
//    @Autowired
//    private List<Object> scheduledTasks;
//
//    @Override
//    public void run(ApplicationArguments args) {
//        System.out.println("=== Scheduled Tasks in the Application Context ===");
//        scheduledTasks.forEach(task -> {
//            System.out.println("Task class: " + task.getClass());
//            System.out.println("Task details: " + task.toString());
//        });
//        System.out.println("==================================================");
//    }
//}