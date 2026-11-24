package tn.smarthealthcare.orchestrator.clients;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Client HTTP avec délais courts : un service lent est traité comme une panne. */
final class Http {

    private Http() {}

    static RestClient client(int timeoutMs) {
        // HTTP/1.1 explicite : le client JDK propose sinon une mise à niveau h2c (en-tête Upgrade) que certains
        // serveurs (uvicorn, par exemple) refusent avec « Invalid HTTP request »
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 2000))).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return RestClient.builder().requestFactory(factory).build();
    }
}
