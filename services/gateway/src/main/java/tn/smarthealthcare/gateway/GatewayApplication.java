package tn.smarthealthcare.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {
    public static void main(String[] args) {
        // Les conteneurs peuvent être recréés avec une nouvelle IP : on ne garde pas les adresses plus de quelques secondes
        java.security.Security.setProperty("networkaddress.cache.ttl", "5");
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "1");
        SpringApplication.run(GatewayApplication.class, args);
    }

    /**
     * Résolution DNS par le JDK (cache court) plutôt que par le résolveur de Netty, qui garde les adresses pendant le TTL
     * du DNS Docker (600 s) : une intervention sur un service recréé donnait des 503 pendant 10 minutes.
     */
    @org.springframework.context.annotation.Bean
    public org.springframework.cloud.gateway.config.HttpClientCustomizer shortLivedDns() {
        return httpClient -> httpClient.resolver(io.netty.resolver.DefaultAddressResolverGroup.INSTANCE);
    }
}
