import { useState } from 'react';
import { api } from '../api.js';
import { Badge, ErrorBox, Loading, PageTitle, useFetch } from '../ui.jsx';

const newUuid = () => (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`);

export default function Ambulances() {
  const { data, error, loading, reload } = useFetch(() => api('/api/portal/ambulances'), []);

  const [form, setForm] = useState({ patientId: 'PAT-000004', severity: 'CRITIQUE', latitude: '36.8065', longitude: '10.1815' });
  const [resultat, setResultat] = useState(null);
  const [erreur, setErreur] = useState(null);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  async function declencher(e) {
    e.preventDefault();
    setResultat(null);
    setErreur(null);
    try {
      const r = await api('/api/portal/emergency/dispatch', {
        method: 'POST',
        body: {
          patientId: form.patientId,
          alertId: newUuid(),
          severity: form.severity,
          location: { latitude: Number(form.latitude), longitude: Number(form.longitude) },
        },
      });
      setResultat(r);
      reload();
    } catch (err) {
      setErreur(err);
    }
  }

  return (
    <>
      <PageTitle title="Ambulances et urgences" subtitle="Flotte et déclenchement manuel d'un envoi (test)" />

      <section className="card">
        <div className="row spread"><h2>Flotte</h2><button className="btn btn-ghost" onClick={reload}>Actualiser</button></div>
        {loading && <Loading />}
        <ErrorBox error={error} />
        {data && (
          <table>
            <thead><tr><th>Ambulance</th><th>Statut</th><th>Latitude</th><th>Longitude</th></tr></thead>
            <tbody>
              {data.map((a) => (
                <tr key={a.id}><td>{a.id}</td><td><Badge value={a.statut} /></td><td>{a.position.latitude}</td><td>{a.position.longitude}</td></tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <form className="card" onSubmit={declencher}>
        <h2>Déclencher une urgence</h2>
        <p className="muted small">Appelle <code>POST /emergency/dispatch</code> (le même endpoint que l'orchestrateur). Chaque envoi utilise un nouvel identifiant d'alerte.</p>
        <div className="row">
          <label>Patient<input value={form.patientId} onChange={set('patientId')} required pattern="PAT-[0-9]{6}" /></label>
          <label>Gravité
            <select value={form.severity} onChange={set('severity')}>
              {['FAIBLE', 'MOYENNE', 'ELEVEE', 'CRITIQUE'].map((s) => <option key={s}>{s}</option>)}
            </select>
          </label>
          <label>Latitude<input value={form.latitude} onChange={set('latitude')} required /></label>
          <label>Longitude<input value={form.longitude} onChange={set('longitude')} required /></label>
          <button className="btn">Envoyer</button>
        </div>
        <ErrorBox error={erreur} />
        {resultat && (
          <div className="alert alert-ok">
            <strong>Envoi {resultat.dispatchId}</strong>
            {resultat.ambulanceId
              ? <p>Ambulance {resultat.ambulanceId}, arrivée estimée dans {resultat.etaMinutes} min.</p>
              : <p>Aucune ambulance : rendez-vous d'urgence {resultat.fallbackAppointmentId}.</p>}
          </div>
        )}
      </form>
    </>
  );
}
