import { Link, useParams } from 'react-router-dom';
import { api } from '../api.js';
import { Badge, ErrorBox, Loading, PageTitle, Pending, useFetch } from '../ui.jsx';

/**
 * Fiche patient. Profil et ordonnances viennent déjà de l'API REST.
 * Le bloc « constantes, alertes et traitement » doit être remplacé par UNE requête GraphQL
 * (contracts/dashboard.graphql, service de B) dès qu'elle sera livrée.
 */
export default function FichePatient() {
  const { id } = useParams();
  const patient = useFetch(() => api(`/api/portal/patients/${id}`), [id]);
  const ordonnances = useFetch(() => api(`/api/portal/patients/${id}/prescriptions`), [id]);

  return (
    <>
      <p><Link to="/patients">← Patients</Link></p>
      <PageTitle title={patient.data ? `${patient.data.prenom} ${patient.data.nom}` : id} subtitle={id} />
      {patient.loading && <Loading />}
      <ErrorBox error={patient.error} />
      {patient.data && (
        <section className="card">
          <h2>Profil</h2>
          <dl className="dl">
            <dt>Sexe</dt><dd>{patient.data.sexe}</dd>
            <dt>Date de naissance</dt><dd>{new Date(patient.data.dateNaissance).toLocaleDateString('fr-FR')}</dd>
            <dt>Service</dt><dd>{patient.data.service}</dd>
            <dt>Pathologie</dt><dd>{patient.data.pathologie}</dd>
            <dt>Niveau de risque</dt><dd><Badge value={patient.data.niveauRisque} /></dd>
            <dt>N° d'assuré</dt><dd>{patient.data.numeroAssure} ({patient.data.tauxRemboursement} %)</dd>
          </dl>
        </section>
      )}

      <section className="card">
        <h2>Ordonnances</h2>
        <ErrorBox error={ordonnances.error} />
        {ordonnances.data && (ordonnances.data.length === 0 ? <p className="muted">Aucune ordonnance.</p> : (
          <ul>
            {ordonnances.data.map((o) => (
              <li key={o.id}><strong>{o.id}</strong> ({o.date}) : {o.medicaments.map((m) => m.nom).join(', ')}</li>
            ))}
          </ul>
        ))}
      </section>

      <Pending title="Constantes vitales, alertes et traitement">
        Ce bloc utilisera une seule requête GraphQL (profil, constantes, alertes, traitement) via
        <code> /api/dashboard/graphql</code>.
      </Pending>
    </>
  );
}
