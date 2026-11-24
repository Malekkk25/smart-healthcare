package tn.smarthealthcare.ehr.server;

import jakarta.xml.ws.Endpoint;
import java.util.HashMap;
import java.util.Map;
import org.apache.cxf.Bus;
import org.apache.cxf.jaxws.EndpointImpl;
import org.apache.cxf.ws.security.wss4j.WSS4JInInterceptor;
import org.apache.wss4j.common.ConfigurationConstants;
import org.apache.wss4j.common.ext.WSPasswordCallback;
import org.apache.wss4j.dom.WSConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WsConfig {

    private static final Logger log = LoggerFactory.getLogger(WsConfig.class);

    /**
     * Publie le service sur /ws/ehr (WSDL : /ws/ehr?wsdl).
     * Si un mot de passe de service est configuré, chaque requête SOAP doit porter un en-tête WS-Security UsernameToken
     * (mot de passe haché avec nonce et date, nonce rejoué refusé) ; sinon elle est refusée par un SOAP Fault.
     * La récupération du WSDL (GET) reste libre.
     */
    @Bean
    public Endpoint ehrEndpoint(Bus bus, EhrServiceImpl implementation,
                                @Value("${ehr.security.username:ehr-client}") String username,
                                @Value("${ehr.security.password:}") String password) {
        EndpointImpl endpoint = new EndpointImpl(bus, implementation);
        endpoint.publish("/ehr");

        if (password == null || password.isBlank()) {
            log.warn("WS-Security DÉSACTIVÉ : aucun mot de passe de service configuré (EHR_SERVICE_PASSWORD)");
            return endpoint;
        }
        Map<String, Object> config = new HashMap<>();
        config.put(ConfigurationConstants.ACTION, ConfigurationConstants.USERNAME_TOKEN);
        config.put(ConfigurationConstants.PASSWORD_TYPE, WSConstants.PW_DIGEST);
        config.put(ConfigurationConstants.PW_CALLBACK_REF, (javax.security.auth.callback.CallbackHandler) callbacks -> {
            for (javax.security.auth.callback.Callback c : callbacks) {
                if (c instanceof WSPasswordCallback pc && username.equals(pc.getIdentifier())) {
                    pc.setPassword(password);
                }
            }
        });
        endpoint.getInInterceptors().add(new WSS4JInInterceptor(config));
        log.info("WS-Security activé : UsernameToken (mot de passe haché) pour le compte « {} »", username);
        return endpoint;
    }
}
