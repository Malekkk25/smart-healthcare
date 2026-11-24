package tn.smarthealthcare.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class OrchestratorApplication {
    public static void main(String[] args) {
        // Les conteneurs peuvent être recréés avec une nouvelle IP : cache DNS court
        java.security.Security.setProperty("networkaddress.cache.ttl", "5");
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "1");
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
