import { Link, useParams } from 'react-router-dom';
import { api, graphql } from '../api.js';
import { Badge, ErrorBox, fmtDateTime, Loading, PageTitle, useFetch } from '../ui.jsx';

// Une seule requête GraphQL pour les constantes, les alertes ouvertes et le traitement (contracts/dashboard.graphql)
const FICHE = `query Fiche($id: ID!) {
  patient(id: $id) {
    latestVital { measuredAt heartRate respRate spo2 systolicBp diastolicBp temperature onOxygen consciousness }
    alerts(status: OPEN) { id raisedAt severity news2Score reason trendWorsening }
    treatment { ordonnances { id date doctorId medicaments { nom posologie dureeJours } } }
  }
}`;

function Constantes({ v }) {
  const items = [
    ['Pouls', v.heartRate, '/min'], ['Fréq. resp.', v.respRate, '/min'], ['SpO2', v.spo2, '%'],
    ['Tension', v.systolicBp != null ? `${v.systolicBp}/${v.diastolicBp}` : null, 'mmHg'],
    ['Température', v.temperature, '°C'], ['Conscience', v.consciousness, ''],
  ];
  return (
    <>
      <div className="vitals">
        {items.map(([nom, val, unite]) => (
          <div className="vital" key={nom}><span>{nom}</span><b>{val ?? '-'}</b><span>{unite}</span></div>
        ))}
      </div>
      <p className="muted small">Mesure du {fmtDateTime(v.measuredAt)}{v.onOxygen ? ' · sous oxygène' : ''}</p>
    </>
  );
}

export default function FichePatient() {
  const { id } = useParams();
  const patient = useFetch(() => api(`/api/portal/patients/${id}`), [id]);
  const fiche = useFetch(() => graphql(FICHE, { id }), [id]);
  // Dernière intervention d'urgence (orchestrateur) : étapes et résumé de la fiche ; absente si le service est arrêté
  const intervention = useFetch(() => api(`/api/orchestrator/workflows?patientId=${id}&limit=1`).catch(() => []), [id]);
  const derniere = intervention.data?.[0];
  // Repli : si le GraphQL est indisponible, les ordonnances viennent directement du REST
  const ordonnancesRest = useFetch(() => (fiche.error ? api(`/api/portal/patients/${id}/prescriptions`) : Promise.resolve(null)),
    [id, fiche.error]);

  const p = fiche.data?.data?.patient;
  const ordonnances = p?.treatment?.ordonnances ?? ordonnancesRest.data ?? [];

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

      {fiche.loading && <Loading>Chargement des constantes et des alertes…</Loading>}
      {fiche.error && (
        <div className="alert alert-error">
          <strong>Constantes et alertes indisponibles</strong>
          <p>{fiche.error.message} Le profil et les ordonnances restent affichés.</p>
        </div>
      )}
      {fiche.data?.errors?.length > 0 && (
        <div className="alert alert-info"><strong>Données partielles</strong><p>{fiche.data.errors.map((e) => e.message).join(' ; ')}</p></div>
      )}

      {p && (
        <>
          <section className="card">
            <h2>Dernières constantes</h2>
            {p.latestVital ? <Constantes v={p.latestVital} /> : <p className="muted">Aucune mesure reçue.</p>}
          </section>
          <section className="card">
            <h2>Alertes ouvertes</h2>
            {p.alerts.length === 0 ? <p className="muted">Aucune alerte ouverte.</p> : (
              <table>
                <thead><tr><th>Date</th><th>Gravité</th><th>NEWS2</th><th>Motif</th></tr></thead>
                <tbody>
                  {p.alerts.map((a) => (
                    <tr key={a.id}>
                      <td>{fmtDateTime(a.raisedAt)}</td><td><Badge value={a.severity} /></td>
                      <td>{a.news2Score}</td><td>{a.reason}{a.trendWorsening ? ' (dégradation progressive)' : ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </>
      )}

      {derniere && (
        <section className={`card alert-card sev-${derniere.severity}`}>
          <div className="row spread">
            <h2>Dernière intervention d'urgence <Badge value={derniere.status} /></h2>
            <span className="muted small">{fmtDateTime(derniere.startedAt)}</span>
          </div>
          {derniere.dossier?.triage && (
            <p>
              <Badge value={derniere.dossier.triage.riskLevel} /> <strong>Risque {derniere.dossier.triage.riskScore}/100</strong>
              {' '}· {derniere.dossier.triage.summary}{' '}
              <span className="muted small">(résumé : {derniere.dossier.triage.source})</span>
            </p>
          )}
          <div className="steps">
            {derniere.steps.map((s) => (
              <div className="step" key={s.name}><strong>{s.name.replaceAll('_', ' ').toLowerCase()}</strong> <Badge value={s.status} />
                <small>{s.detail || '...'}</small></div>
            ))}
          </div>
          {derniere.adminAlert && <p className="alert alert-error">{derniere.adminAlert}</p>}
        </section>
      )}

      <section className="card">
        <h2>Traitement (ordonnances)</h2>
        {ordonnances.length === 0 ? <p className="muted">Aucune ordonnance.</p> : (
          <ul>
            {ordonnances.map((o) => (
              <li key={o.id}><strong>{o.id}</strong> ({o.date}) : {o.medicaments.map((m) => `${m.nom} - ${m.posologie}`).join(' ; ')}</li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
