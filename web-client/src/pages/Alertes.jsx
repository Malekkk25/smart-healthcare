import { PageTitle, Pending } from '../ui.jsx';

/**
 * Flux d'alertes en direct.
 * À brancher sur la subscription GraphQL `alertCreated` (WebSocket : /api/dashboard/graphql?access_token=<jwt>)
 * dès que contracts/dashboard.graphql et le service graphql-dashboard de B sont livrés.
 */
export default function Alertes() {
  return (
    <>
      <PageTitle title="Alertes en direct" subtitle="Alertes critiques reçues sans rafraîchir la page" />
      <Pending title="Subscription GraphQL alertCreated">
        La Gateway relaie déjà les WebSocket vers <code>graphql-dashboard:8083</code> (route <code>dashboard-ws</code>).
        Il manque le contrat <code>dashboard.graphql</code> et le service de B.
      </Pending>
    </>
  );
}
