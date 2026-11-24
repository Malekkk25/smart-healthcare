import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { Badge, ErrorBox, PageTitle } from '../ui.jsx';

// Grand Tunis (schéma sans fond de carte : fonctionne hors ligne)
const BOUNDS = { latMin: 36.72, latMax: 36.90, lonMin: 10.05, lonMax: 10.32 };
const W = 800;
const H = 520;
/** 1 seconde réelle = 1 minute simulée : une ambulance à 6 minutes arrive en 6 secondes à l'écran. */
const ACCELERATION = 60;

const x = (lon) => ((lon - BOUNDS.lonMin) / (BOUNDS.lonMax - BOUNDS.lonMin)) * W;
const y = (lat) => H - ((lat - BOUNDS.latMin) / (BOUNDS.latMax - BOUNDS.latMin)) * H;
const lerp = (a, b, t) => a + (b - a) * t;

export default function Carte() {
  const [ambulances, setAmbulances] = useState([]);
  const [interventions, setInterventions] = useState([]);
  const [erreur, setErreur] = useState(null);
  const [now, setNow] = useState(Date.now());

  // Données rafraîchies toutes les 3 s ; horloge d'animation chaque seconde
  useEffect(() => {
    let actif = true;
    const charger = () => Promise.all([api('/api/portal/ambulances'), api('/api/orchestrator/workflows?limit=30')])
      .then(([a, w]) => { if (actif) { setAmbulances(a); setInterventions(w); setErreur(null); } })
      .catch((e) => { if (actif) setErreur(e); });
    charger();
    const donnees = setInterval(charger, 3000);
    const horloge = setInterval(() => setNow(Date.now()), 1000);
    return () => { actif = false; clearInterval(donnees); clearInterval(horloge); };
  }, []);

  const parId = useMemo(() => Object.fromEntries(ambulances.map((a) => [a.id, a])), [ambulances]);

  // Interventions avec ambulance : trajet de la base de l'ambulance jusqu'au domicile du patient
  const trajets = useMemo(() => interventions
    .filter((w) => w.ambulanceId && w.etaMinutes != null && w.latitude != null && parId[w.ambulanceId])
    .map((w) => {
      const base = parId[w.ambulanceId].position;
      const dureeMs = (w.etaMinutes * 60000) / ACCELERATION;
      const progression = Math.min(1, Math.max(0, (now - new Date(w.startedAt).getTime()) / dureeMs));
      return {
        id: w.id, ambulanceId: w.ambulanceId, patientId: w.patientId, eta: w.etaMinutes, progression,
        restant: Math.ceil(w.etaMinutes * (1 - progression)),
        base, domicile: { latitude: w.latitude, longitude: w.longitude },
        position: { latitude: lerp(base.latitude, w.latitude, progression), longitude: lerp(base.longitude, w.longitude, progression) },
      };
    })
    .filter((t) => now - new Date(interventions.find((w) => w.id === t.id).startedAt).getTime() < 120000), // 2 min d'affichage
  [interventions, parId, now]);

  const enRoute = new Set(trajets.filter((t) => t.progression < 1).map((t) => t.ambulanceId));

  return (
    <>
      <PageTitle title="Carte des ambulances" subtitle="Positions des ambulances et trajets vers le domicile des patients" />
      <ErrorBox error={erreur} />
      <div className="legend">
        <span><i style={{ background: 'var(--ok)' }} />ambulance disponible</span>
        <span><i style={{ background: 'var(--warn)' }} />en intervention</span>
        <span><i style={{ background: 'var(--muted)' }} />hors service</span>
        <span><i style={{ background: 'var(--bad)' }} />domicile du patient (simulé)</span>
        <span>Temps accéléré x{ACCELERATION} : 1 s = 1 min</span>
      </div>

      <svg className="map" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Carte schématique du Grand Tunis">
        {[0, 1, 2, 3, 4].map((i) => (
          <g key={i}>
            <line className="grid" x1={(W / 4) * i} y1="0" x2={(W / 4) * i} y2={H} />
            <line className="grid" x1="0" y1={(H / 4) * i} x2={W} y2={(H / 4) * i} />
          </g>
        ))}
        <text className="muted-text" x="8" y={H - 8}>Grand Tunis (schéma) · {BOUNDS.latMin}°N {BOUNDS.lonMin}°E</text>

        {trajets.map((t) => (
          <g key={t.id}>
            <line className="route" x1={x(t.base.longitude)} y1={y(t.base.latitude)} x2={x(t.domicile.longitude)} y2={y(t.domicile.latitude)} />
            <circle className="home" cx={x(t.domicile.longitude)} cy={y(t.domicile.latitude)} r="8" />
            <text x={x(t.domicile.longitude) + 12} y={y(t.domicile.latitude) + 4}>{t.patientId}</text>
          </g>
        ))}

        {ambulances.map((a) => {
          const trajet = trajets.find((t) => t.ambulanceId === a.id && t.progression < 1) || trajets.find((t) => t.ambulanceId === a.id);
          const pos = trajet ? trajet.position : a.position;
          return (
            <g key={a.id}>
              <rect className={`amb-${a.statut}`} x={x(pos.longitude) - 8} y={y(pos.latitude) - 8} width="16" height="16" rx="3" />
              <text x={x(pos.longitude) + 12} y={y(pos.latitude) - 6}>{a.id}</text>
              {trajet && enRoute.has(a.id) && (
                <text className="muted-text" x={x(pos.longitude) + 12} y={y(pos.latitude) + 8}>arrivée dans {trajet.restant} min</text>
              )}
            </g>
          );
        })}
      </svg>

      <section className="card" style={{ marginTop: 16 }}>
        <h2>Interventions en cours d'affichage</h2>
        {trajets.length === 0 ? <p className="muted">Aucune intervention avec ambulance dans les 2 dernières minutes. Lancez un scénario du simulateur de capteurs.</p> : (
          <table>
            <thead><tr><th>Patient</th><th>Ambulance</th><th>Délai annoncé</th><th>Progression</th></tr></thead>
            <tbody>
              {trajets.map((t) => (
                <tr key={t.id}>
                  <td><Link to={`/patients/${t.patientId}`}>{t.patientId}</Link></td>
                  <td>{t.ambulanceId}</td><td>{t.eta} min</td>
                  <td>{t.progression >= 1 ? <Badge value="SUCCEEDED" /> : `${Math.round(t.progression * 100)} % (encore ${t.restant} min)`}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="card">
        <h2>Flotte</h2>
        <table>
          <thead><tr><th>Ambulance</th><th>Statut</th><th>Latitude</th><th>Longitude</th></tr></thead>
          <tbody>
            {ambulances.map((a) => (
              <tr key={a.id}><td>{a.id}</td><td><Badge value={a.statut} /></td><td>{a.position.latitude}</td><td>{a.position.longitude}</td></tr>
            ))}
          </tbody>
        </table>
      </section>
    </>
  );
}
