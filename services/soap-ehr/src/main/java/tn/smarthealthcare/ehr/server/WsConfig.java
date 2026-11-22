package tn.smarthealthcare.ehr.server;

import jakarta.xml.ws.Endpoint;
import org.apache.cxf.Bus;
import org.apache.cxf.jaxws.EndpointImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WsConfig {

    /** Publie le service sur /ws/ehr (WSDL : /ws/ehr?wsdl). */
    @Bean
    public Endpoint ehrEndpoint(Bus bus, EhrServiceImpl implementation) {
        EndpointImpl endpoint = new EndpointImpl(bus, implementation);
        endpoint.publish("/ehr");
        return endpoint;
    }
}
