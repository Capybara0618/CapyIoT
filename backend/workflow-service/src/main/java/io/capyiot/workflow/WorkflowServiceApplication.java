package io.capyiot.workflow;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication(scanBasePackages="io.capyiot.workflow.work")
@EnableScheduling public class WorkflowServiceApplication {public static void main(String[] args){SpringApplication.run(WorkflowServiceApplication.class,args);}}
