package tn.smarthealthcare.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Filtre « SoapSecurity » (voir application.yml) : réécrit le corps des requêtes SOAP pour y ajouter l'en-tête WS-Security
 * du compte de service. Il s'exécute après l'authentification JWT, donc seuls les utilisateurs connectés atteignent le SOAP.
 */
@Component
public class SoapSecurityGatewayFilterFactory extends AbstractGatewayFilterFactory<SoapSecurityGatewayFilterFactory.Config> {

    public static class Config {}

    private final ModifyRequestBodyGatewayFilterFactory modifyBody;
    private final AppProperties props;

    public SoapSecurityGatewayFilterFactory(ModifyRequestBodyGatewayFilterFactory modifyBody, AppProperties props) {
        super(Config.class);
        this.modifyBody = modifyBody;
        this.props = props;
    }

    @Override
    public GatewayFilter apply(Config config) {
        var ehr = props.ehr();
        var rewrite = new ModifyRequestBodyGatewayFilterFactory.Config()
                .setRewriteFunction(String.class, String.class,
                        (exchange, body) -> Mono.just(SoapSecurityInjector.inject(body, ehr.username(), ehr.password())));
        return modifyBody.apply(rewrite);
    }
}
