import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { graphql, subscribe } from '../api.js';
import { Badge, ErrorBox, fmtDateTime, PageTitle } from '../ui.jsx';

const CHAMPS = 'id patientId raisedAt severity news2Score reason trendWorsening status patient { nom prenom service }';
const RECENTES = `{ alerts(status: OPEN, limit: 30) { ${CHAMPS} } }`;
const FLUX = `subscription { alertCreated(minSeverity: MOYENNE) { ${CHAMPS} } }`;

export default function Alertes() {
  const [alertes, setAlertes] = useState([]);
  const [statut, setStatut] = useState('connexion');
  const [erreur, setErreur] = useState(null);
  const nouvelles = useRef(new Set());

  useEffect(() => {
    let actif = true;
    graphql(RECENTES).then((r) => { if (actif) setAlertes(r.data.alerts); }, (e) => { if (actif) setErreur(e); });
    const arreter = subscribe(FLUX, {}, {
      onStatus: (s) => actif && setStatut(s),
      onData: (d) => {
        if (!actif) return;
        const a = d.alertCreated;
        nouvelles.current.add(a.id);
        setAlertes((liste) => [a, ...liste.filter((x) => x.id !== a.id)].slice(0, 100));
        setTimeout(() => nouvelles.current.delete(a.id), 3000);
      },
    });
    return () => { actif = false; arreter(); };
  }, []);

  return (
    <>
      <PageTitle title="Alertes en direct" subtitle="Les alertes arrivent par subscription GraphQL, sans rafraîchir la page" />
      <p>
        <span className={`status-dot ${statut === 'connecté' ? 'on' : statut === 'connexion' ? '' : 'off'}`} />
        Flux : <strong>{statut}</strong> · {alertes.length} alerte(s) ouverte(s)
      </p>
      <ErrorBox error={erreur} />
      {alertes.length === 0 && !erreur && <p className="muted">Aucune alerte ouverte pour le moment.</p>}
      {alertes.map((a) => (
        <section key={a.id} className={`card alert-card sev-${a.severity} ${nouvelles.current.has(a.id) ? 'nouvelle' : ''}`}>
          <div className="row spread">
            <h2>
              <Badge value={a.severity} />{' '}
              <Link to={`/patients/${a.patientId}`}>{a.patient ? `${a.patient.prenom} ${a.patient.nom}` : a.patientId}</Link>
              <span className="muted small"> · {a.patientId}{a.patient?.service ? ` · ${a.patient.service}` : ''}</span>
            </h2>
            <span className="muted small">{fmtDateTime(a.raisedAt)}</span>
          </div>
          <p>NEWS2 <strong>{a.news2Score}</strong> · {a.reason}{a.trendWorsening ? ' · dégradation progressive' : ''}</p>
        </section>
      ))}
    </>
  );
}
