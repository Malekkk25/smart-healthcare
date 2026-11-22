import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import { ErrorBox, Loading, PageTitle, useFetch } from '../ui.jsx';

export default function Ordonnances() {
  const { session } = useAuth();
  const { data, error, loading } = useFetch(() => api(`/api/portal/patients/${session.patientId}/prescriptions`), []);

  return (
    <>
      <PageTitle title="Mes ordonnances" subtitle={`Patient ${session.patientId}`} />
      {loading && <Loading />}
      <ErrorBox error={error} />
      {data && data.length === 0 && <p className="muted">Aucune ordonnance.</p>}
      {data && data.map((o) => (
        <section className="card" key={o.id}>
          <h2>{o.id} <span className="muted small">du {new Date(o.date).toLocaleDateString('fr-FR')} · médecin {o.doctorId}</span></h2>
          <table>
            <thead><tr><th>Médicament</th><th>Posologie</th><th>Durée</th></tr></thead>
            <tbody>
              {o.medicaments.map((m) => (
                <tr key={m.nom}><td>{m.nom}</td><td>{m.posologie}</td><td>{m.dureeJours ? `${m.dureeJours} jours` : '-'}</td></tr>
              ))}
            </tbody>
          </table>
        </section>
      ))}
    </>
  );
}
