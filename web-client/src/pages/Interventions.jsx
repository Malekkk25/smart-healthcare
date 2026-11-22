import { PageTitle, Pending } from '../ui.jsx';

/**
 * Liste des interventions et suivi des étapes du workflow d'urgence.
 * À brancher sur GET /api/orchestrator/workflows/{id} et le flux SSE des étapes
 * (contracts/workflow-api.md, service de B).
 */
export default function Interventions() {
  return (
    <>
      <PageTitle title="Interventions" subtitle="Suivi des workflows d'urgence (4 étapes, avec replis)" />
      <Pending title="API de l'orchestrateur">
        La Gateway route déjà <code>/api/orchestrator/**</code> vers <code>orchestrator:8085</code>.
        Il manque le contrat <code>workflow-api.md</code> et le service de B.
      </Pending>
    </>
  );
}
