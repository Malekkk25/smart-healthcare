import { useState } from 'react';
import { api } from '../api.js';
import { ErrorBox, fmtTime, Loading, PageTitle, useFetch } from '../ui.jsx';

const MEDECINS = ['', 'DOC-001', 'DOC-002', 'DOC-003'];

export default function Creneaux() {
  const [doctorId, setDoctorId] = useState('');
  const [date, setDate] = useState(new Date(Date.now() + 86400000).toISOString().slice(0, 10));

  const qs = new URLSearchParams();
  if (doctorId) qs.set('doctorId', doctorId);
  if (date) qs.set('date', date);
  const { data, error, loading, reload } = useFetch(() => api(`/api/portal/slots?${qs}`), [doctorId, date]);

  const urgences = (data || []).filter((c) => c.urgence).length;

  return (
    <>
      <PageTitle title="Créneaux" subtitle="Disponibilités des médecins (créneaux libres uniquement)" />
      <div className="card row">
        <label>Médecin
          <select value={doctorId} onChange={(e) => setDoctorId(e.target.value)}>
            {MEDECINS.map((m) => <option key={m} value={m}>{m || 'Tous'}</option>)}
          </select>
        </label>
        <label>Date<input type="date" value={date} onChange={(e) => setDate(e.target.value)} /></label>
        <button className="btn btn-ghost" onClick={reload}>Actualiser</button>
      </div>
      {loading && <Loading />}
      <ErrorBox error={error} />
      {data && (
        <section className="card">
          <p><strong>{data.length}</strong> créneaux libres, dont <strong>{urgences}</strong> réservés aux urgences.</p>
          <table>
            <thead><tr><th>Créneau</th><th>Médecin</th><th>Début</th><th>Fin</th><th>Type</th></tr></thead>
            <tbody>
              {data.map((c) => (
                <tr key={c.id}>
                  <td>{c.id}</td><td>{c.doctorId}</td><td>{fmtTime(c.debut)}</td><td>{fmtTime(c.fin)}</td>
                  <td>{c.urgence ? <span className="badge badge-bad">urgence</span> : 'normal'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </>
  );
}
