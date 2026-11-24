import { useCallback, useEffect, useState } from 'react';

/** Charge des données ; relance quand deps change ou via reload(). */
export function useFetch(fn, deps = []) {
  const [state, setState] = useState({ data: null, error: null, loading: true });
  const run = useCallback(() => {
    setState((s) => ({ ...s, loading: true, error: null }));
    fn().then(
      (data) => setState({ data, error: null, loading: false }),
      (error) => setState({ data: null, error, loading: false }),
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  useEffect(() => { run(); }, [run]);
  return { ...state, reload: run };
}

export function ErrorBox({ error }) {
  if (!error) return null;
  return (
    <div className="alert alert-error" role="alert">
      <strong>{error.message}</strong>
      {error.correlationId && <div className="muted small">Code {error.code} · corrélation {error.correlationId}</div>}
    </div>
  );
}

export function Loading({ children = 'Chargement…' }) {
  return <p className="muted">{children}</p>;
}

export function Badge({ value }) {
  const tone = {
    DISPONIBLE: 'ok', CONFIRME: 'ok', FAIBLE: 'ok',
    EN_INTERVENTION: 'warn', MOYEN: 'warn', ANNULE: 'muted',
    HORS_SERVICE: 'bad', ELEVE: 'bad',
    CRITIQUE: 'bad', ELEVEE: 'bad', MOYENNE: 'warn',
    COMPLETED: 'ok', SUCCEEDED: 'ok', DEGRADED: 'warn', RUNNING: 'info', FAILED: 'bad', PENDING: 'muted',
    OPEN: 'bad', ACKNOWLEDGED: 'warn', CLOSED: 'muted',
  }[value] || 'muted';
  const libelle = {
    COMPLETED: 'terminée', SUCCEEDED: 'réussie', DEGRADED: 'mode dégradé', FAILED: 'échec', RUNNING: 'en cours',
    PENDING: 'en attente', OPEN: 'ouverte', ACKNOWLEDGED: 'prise en compte', CLOSED: 'fermée',
    CONFIRME: 'confirmé', ANNULE: 'annulé', ELEVEE: 'élevée', ELEVE: 'élevé',
  }[value];
  return <span className={`badge badge-${tone}`}>{libelle || String(value).replace('_', ' ').toLowerCase()}</span>;
}

export function PageTitle({ title, subtitle }) {
  return (
    <header className="page-title">
      <h1>{title}</h1>
      {subtitle && <p className="muted">{subtitle}</p>}
    </header>
  );
}

export function Pending({ title, owner = 'personne B', children }) {
  return (
    <div className="alert alert-info">
      <strong>{title}</strong>
      <p>En attente de la livraison de {owner}. {children}</p>
    </div>
  );
}

export const fmtDateTime = (iso) =>
  new Date(iso).toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Africa/Tunis' });
export const fmtTime = (iso) =>
  new Date(iso).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', timeZone: 'Africa/Tunis' });
