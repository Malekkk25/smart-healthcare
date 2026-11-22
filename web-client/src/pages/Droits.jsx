import { useState } from 'react';
import { ApiError, SoapFault, soapCall, toList } from '../api.js';
import { useAuth } from '../auth.jsx';
import { ErrorBox, PageTitle } from '../ui.jsx';

// Lettres-clés de la nomenclature fictive du service EHR
const ACTES = [
  ['C', 'C - Consultation généraliste'],
  ['CS', 'CS - Consultation spécialiste'],
  ['K', 'K - Acte de spécialité'],
  ['KC', 'KC - Chirurgie cardiaque'],
  ['Z', 'Z - Imagerie'],
  ['B', 'B - Biologie'],
  ['XX', 'XX - Acte hors nomenclature (test)'],
];

function Resultat({ erreur }) {
  if (!erreur) return null;
  if (erreur instanceof SoapFault) {
    if (erreur.type === 'DroitsExpiresFault') {
      return (
        <div className="alert alert-error">
          <strong>Droits expirés</strong>
          <p>{erreur.detail.message} (expirés le {erreur.detail.dateExpiration}).</p>
        </div>
      );
    }
    if (erreur.type === 'PatientInconnuFault') {
      return <div className="alert alert-error"><strong>Patient inconnu</strong><p>{erreur.detail.message} ({erreur.detail.patientId}).</p></div>;
    }
    return <div className="alert alert-error"><strong>{erreur.message}</strong></div>;
  }
  if (erreur instanceof ApiError) return <ErrorBox error={erreur} />;
  return <div className="alert alert-error">{String(erreur)}</div>;
}

export default function Droits() {
  const { session } = useAuth();
  const estPatient = session.role === 'PATIENT';
  const [patientId, setPatientId] = useState(session.patientId || 'PAT-000002');
  const [codeActe, setCodeActe] = useState('CS');
  const [droits, setDroits] = useState(null);
  const [historique, setHistorique] = useState(null);
  const [erreur, setErreur] = useState(null);
  const [busy, setBusy] = useState(false);

  async function verifier(e) {
    e.preventDefault();
    setBusy(true);
    setErreur(null);
    setDroits(null);
    setHistorique(null);
    try {
      const d = await soapCall('verifierDroitsCNAM', { patientId, codeActe });
      setDroits({ ouverts: d.droitsOuverts === 'true', taux: d.tauxRemboursement, motif: d.motif });
      const h = await soapCall('consulterHistorique', { patientId });
      setHistorique(toList(h.acte));
    } catch (err) {
      setErreur(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageTitle title="Droits CNAM" subtitle="Vérification via le service SOAP du dossier patient (enveloppe construite par le navigateur)" />
      <form className="card" onSubmit={verifier}>
        <div className="row">
          <label>Patient
            <input value={patientId} onChange={(e) => setPatientId(e.target.value)} readOnly={estPatient}
                   pattern="[A-Za-z0-9\-]{3,20}" required />
          </label>
          <label>Acte
            <select value={codeActe} onChange={(e) => setCodeActe(e.target.value)}>
              {ACTES.map(([code, libelle]) => <option key={code} value={code}>{libelle}</option>)}
            </select>
          </label>
          <button className="btn" disabled={busy}>{busy ? 'Vérification…' : 'Vérifier mes droits'}</button>
        </div>
      </form>

      <Resultat erreur={erreur} />

      {droits && (
        <section className={`card ${droits.ouverts ? 'card-ok' : 'card-bad'}`}>
          <h2>{droits.ouverts ? 'Droits ouverts' : 'Acte non pris en charge'}</h2>
          <p>Taux de remboursement : <strong>{droits.taux} %</strong></p>
          <p className="muted">{droits.motif}</p>
        </section>
      )}

      {historique && (
        <section className="card">
          <h2>Actes certifiés</h2>
          {historique.length === 0 ? <p className="muted">Aucun acte certifié.</p> : (
            <table>
              <thead><tr><th>Date</th><th>Acte</th><th>Montant (DT)</th><th>Praticien</th></tr></thead>
              <tbody>
                {historique.map((a, i) => (
                  <tr key={i}><td>{a.date}</td><td>{a.code}</td><td>{Number(a.montant).toFixed(3)}</td><td>{a.praticien}</td></tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}
    </>
  );
}
