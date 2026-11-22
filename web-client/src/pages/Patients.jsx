import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { Badge, ErrorBox, Loading, PageTitle, useFetch } from '../ui.jsx';

const SERVICES = ['cardiologie', 'pneumologie', 'geriatrie', 'medecine generale'];
const RISQUES = ['FAIBLE', 'MOYEN', 'ELEVE'];

export default function Patients() {
  const [service, setService] = useState('');
  const [niveauRisque, setNiveauRisque] = useState('');
  const [recherche, setRecherche] = useState('');

  const qs = new URLSearchParams();
  if (service) qs.set('service', service);
  if (niveauRisque) qs.set('niveauRisque', niveauRisque);
  const { data, error, loading } = useFetch(() => api(`/api/portal/patients?${qs}`), [service, niveauRisque]);

  const visibles = (data || []).filter((p) =>
    `${p.id} ${p.nom} ${p.prenom} ${p.pathologie}`.toLowerCase().includes(recherche.toLowerCase()));

  return (
    <>
      <PageTitle title="Patients" subtitle="Liste filtrable (source : API REST du portail)" />
      <div className="card row">
        <label className="grow">Recherche
          <input value={recherche} onChange={(e) => setRecherche(e.target.value)} placeholder="Nom, identifiant, pathologie…" />
        </label>
        <label>Service
          <select value={service} onChange={(e) => setService(e.target.value)}>
            <option value="">Tous</option>{SERVICES.map((s) => <option key={s}>{s}</option>)}
          </select>
        </label>
        <label>Niveau de risque
          <select value={niveauRisque} onChange={(e) => setNiveauRisque(e.target.value)}>
            <option value="">Tous</option>{RISQUES.map((r) => <option key={r}>{r}</option>)}
          </select>
        </label>
      </div>
      {loading && <Loading />}
      <ErrorBox error={error} />
      {data && (
        <table className="card">
          <thead><tr><th>Identifiant</th><th>Patient</th><th>Service</th><th>Pathologie</th><th>Risque</th></tr></thead>
          <tbody>
            {visibles.map((p) => (
              <tr key={p.id}>
                <td><Link to={`/patients/${p.id}`}>{p.id}</Link></td>
                <td>{p.prenom} {p.nom}</td><td>{p.service}</td><td>{p.pathologie}</td>
                <td><Badge value={p.niveauRisque} /></td>
              </tr>
            ))}
            {visibles.length === 0 && <tr><td colSpan="5" className="muted">Aucun patient ne correspond.</td></tr>}
          </tbody>
        </table>
      )}
    </>
  );
}
