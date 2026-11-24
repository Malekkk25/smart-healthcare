import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, streamEvents } from '../api.js';
import { Badge, ErrorBox, fmtDateTime, Loading, PageTitle } from '../ui.jsx';

const LIBELLES = { ALERTE: '1. Alerte', DROITS: '2. Droits', PRISE_EN_CHARGE: '3. Prise en charge', FICHE: '4. Fiche' };

/** Suivi des interventions : liste initiale (REST) puis mises à jour en direct (SSE) de l'orchestrateur. */
export default function Interventions() {
  const [liste, setListe] = useState(null);
  const [erreur, setErreur] = useState(null);
  const [statut, setStatut] = useState('connexion');
  const [alertesAdmin, setAlertesAdmin] = useState([]);

  useEffect(() => {
    let actif = true;
    const maj = (w) => setListe((l) => [w, ...(l || []).filter((x) => x.id !== w.id)]);

    api('/api/orchestrator/workflows?limit=50').then((l) => { if (actif) setListe(l); }, (e) => { if (actif) { setErreur(e); setListe([]); } });
    const arreter = streamEvents('/api/orchestrator/workflows/events', {
      onStatus: (s) => actif && setStatut(s),
      onEvent: (event, data) => {
        if (!actif) return;
        if (event === 'WORKFLOW_STARTED' || event === 'WORKFLOW_ENDED') maj(data);
        else if (event === 'STEP_UPDATED') {
          setListe((l) => (l || []).map((w) => (w.id !== data.workflowId ? w
            : { ...w, steps: w.steps.map((s) => (s.name === data.step.name ? data.step : s)) })));
        } else if (event === 'ADMIN_ALERT') {
          setAlertesAdmin((a) => [data, ...a].slice(0, 10));
        }
      },
    });
    return () => { actif = false; arreter(); };
  }, []);

  return (
    <>
      <PageTitle title="Interventions" subtitle="Workflow d'urgence en 4 étapes, avec repli à chaque étape" />
      <p>
        <span className={`status-dot ${statut === 'connecté' ? 'on' : statut === 'connexion' ? '' : 'off'}`} />
        Flux en direct : <strong>{statut}</strong>
      </p>
      <ErrorBox error={erreur} />
      {alertesAdmin.map((a, i) => (
        <div className="alert alert-error" key={i}><strong>Alerte administrateur</strong><p>{a.message}</p></div>
      ))}
      {liste === null && <Loading />}
      {liste && liste.length === 0 && !erreur && <p className="muted">Aucune intervention.</p>}
      {liste && liste.map((w) => (
        <section className="card" key={w.id}>
          <div className="row spread">
            <h2>
              <Link to={`/patients/${w.patientId}`}>{w.patientId}</Link> <Badge value={w.status} />
              <span className="muted small"> · alerte {w.alertId.slice(0, 8)}</span>
            </h2>
            <span className="muted small">{fmtDateTime(w.startedAt)}</span>
          </div>
          <div className="steps">
            {w.steps.map((s) => (
              <div className="step" key={s.name}>
                <strong>{LIBELLES[s.name] || s.name}</strong> <Badge value={s.status} />
                <small>{s.detail || '...'}{s.attempts > 1 ? ` (${s.attempts} essais)` : ''}</small>
              </div>
            ))}
          </div>
          {w.adminAlert && <p className="alert alert-error">{w.adminAlert}</p>}
          <p className="muted small">Corrélation {w.correlationId}</p>
        </section>
      ))}
    </>
  );
}
