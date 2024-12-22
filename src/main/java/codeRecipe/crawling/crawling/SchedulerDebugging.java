package codeRecipe.crawling.crawling;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SchedulerDebugging {
    @Autowired
    private ApplicationContext applicationContext;

    @PostConstruct
    public void inspectSchedulers() {
        Map<String, TaskScheduler> schedulers = applicationContext.getBeansOfType(TaskScheduler.class);
        schedulers.forEach((name, scheduler) ->
                System.out.println("TaskScheduler Bean: " + name + " -> " + scheduler.getClass().getName())
        );
    }
}
