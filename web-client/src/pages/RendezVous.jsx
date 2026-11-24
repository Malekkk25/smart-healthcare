import { useState } from 'react';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import { Badge, ErrorBox, fmtDateTime, fmtTime, Loading, PageTitle, useFetch } from '../ui.jsx';

const MEDECINS = ['DOC-001', 'DOC-002', 'DOC-003'];

const demain = () => {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  return d.toISOString().slice(0, 10);
};

export default function RendezVous() {
  const { session } = useAuth();
  const patientId = session.patientId;
  const liste = useFetch(() => api(`/api/portal/appointments?patientId=${patientId}`), [patientId]);

  const [doctorId, setDoctorId] = useState(MEDECINS[0]);
  const [date, setDate] = useState(demain());
  const [motif, setMotif] = useState('');
  const [message, setMessage] = useState(null);
  const [erreur, setErreur] = useState(null);
  const creneaux = useFetch(() => api(`/api/portal/slots?doctorId=${doctorId}&date=${date}`), [doctorId, date]);

  async function reserver(slotId) {
    setErreur(null);
    setMessage(null);
    try {
      const rdv = await api('/api/portal/appointments', { method: 'POST', body: { patientId, slotId, motif: motif || undefined } });
      setMessage(`Rendez-vous ${rdv.id} confirmé le ${fmtDateTime(rdv.debut)}.`);
      liste.reload();
      creneaux.reload();
    } catch (e) {
      setErreur(e);
      creneaux.reload();
    }
  }

  async function annuler(id) {
    setErreur(null);
    setMessage(null);
    try {
      await api(`/api/portal/appointments/${id}`, { method: 'DELETE' });
      setMessage(`Rendez-vous ${id} annulé.`);
      liste.reload();
      creneaux.reload();
    } catch (e) {
      setErreur(e);
    }
  }

  return (
    <>
      <PageTitle title="Mes rendez-vous" subtitle={`Patient ${patientId}`} />
      {message && <div className="alert alert-ok">{message}</div>}
      <ErrorBox error={erreur} />

      <section className="card">
        <h2>Rendez-vous planifiés</h2>
        {liste.loading && <Loading />}
        <ErrorBox error={liste.error} />
        {liste.data && (liste.data.length === 0 ? <p className="muted">Aucun rendez-vous.</p> : (
          <table>
            <thead><tr><th>Référence</th><th>Date</th><th>Médecin</th><th>Motif</th><th>Statut</th><th /></tr></thead>
            <tbody>
              {liste.data.map((r) => (
                <tr key={r.id}>
                  <td>{r.id}</td><td>{fmtDateTime(r.debut)}</td><td>{r.doctorId}</td><td>{r.motif || '-'}</td>
                  <td><Badge value={r.statut} /></td>
                  <td>
                    {r.visioUrl && <a className="btn btn-ghost" href={r.visioUrl} target="_blank" rel="noreferrer">Visio</a>}{' '}
                    {r.statut === 'CONFIRME' && <button className="btn btn-ghost" onClick={() => annuler(r.id)}>Annuler</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ))}
      </section>

      <section className="card">
        <h2>Prendre un rendez-vous</h2>
        <div className="row">
          <label>Médecin
            <select value={doctorId} onChange={(e) => setDoctorId(e.target.value)}>
              {MEDECINS.map((m) => <option key={m}>{m}</option>)}
            </select>
          </label>
          <label>Date
            <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
          </label>
          <label className="grow">Motif (facultatif)
            <input value={motif} onChange={(e) => setMotif(e.target.value)} placeholder="Ex. suivi de tension" />
          </label>
        </div>
        {creneaux.loading && <Loading>Recherche des créneaux…</Loading>}
        <ErrorBox error={creneaux.error} />
        {creneaux.data && (creneaux.data.length === 0 ? <p className="muted">Aucun créneau libre ce jour-là.</p> : (
          <div className="slots">
            {creneaux.data.map((c) => (
              <button key={c.id} className={`slot ${c.urgence ? 'slot-urgence' : ''}`} onClick={() => reserver(c.id)}
                      title={c.urgence ? "Créneau réservé aux urgences" : 'Réserver'}>
                {fmtTime(c.debut)}
              </button>
            ))}
          </div>
        ))}
      </section>
    </>
  );
}
