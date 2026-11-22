import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import { Badge, ErrorBox, Loading, PageTitle, useFetch } from '../ui.jsx';

export default function Profil() {
  const { session } = useAuth();
  const id = session.patientId;
  const { data, error, loading, reload } = useFetch(() => api(`/api/portal/patients/${id}`), [id]);
  const [form, setForm] = useState(null);
  const [message, setMessage] = useState(null);
  const [erreur, setErreur] = useState(null);

  useEffect(() => { if (data) setForm({ ...data }); }, [data]);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  async function enregistrer(e) {
    e.preventDefault();
    setMessage(null);
    setErreur(null);
    try {
      await api(`/api/portal/patients/${id}`, {
        method: 'PUT',
        body: {
          nom: form.nom, prenom: form.prenom, sexe: form.sexe, dateNaissance: form.dateNaissance,
          service: form.service, pathologie: form.pathologie,
        },
      });
      setMessage('Profil mis à jour.');
      reload();
    } catch (err) {
      setErreur(err);
    }
  }

  return (
    <>
      <PageTitle title="Mon profil" subtitle={`Patient ${id}`} />
      {loading && <Loading />}
      <ErrorBox error={error} />
      {form && (
        <form className="card" onSubmit={enregistrer}>
          <div className="grid2">
            <label>Nom<input value={form.nom} onChange={set('nom')} required /></label>
            <label>Prénom<input value={form.prenom} onChange={set('prenom')} required /></label>
            <label>Sexe
              <select value={form.sexe} onChange={set('sexe')}><option value="M">M</option><option value="F">F</option></select>
            </label>
            <label>Date de naissance<input type="date" value={form.dateNaissance} onChange={set('dateNaissance')} required /></label>
            <label>Service<input value={form.service || ''} onChange={set('service')} /></label>
            <label>Pathologie<input value={form.pathologie || ''} onChange={set('pathologie')} /></label>
          </div>
          <p className="muted">
            N° d'assuré {form.numeroAssure} · remboursement {form.tauxRemboursement} % · niveau de risque <Badge value={form.niveauRisque} />
          </p>
          {message && <div className="alert alert-ok">{message}</div>}
          <ErrorBox error={erreur} />
          <button className="btn">Enregistrer</button>
        </form>
      )}
    </>
  );
}
