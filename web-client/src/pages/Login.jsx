import { useState } from 'react';
import { useAuth } from '../auth.jsx';
import { ErrorBox } from '../ui.jsx';

export default function Login() {
  const { login } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username.trim(), password);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-wrap">
      <form className="card login" onSubmit={submit}>
        <h1>Smart Healthcare</h1>
        <p className="muted">Connexion à la plateforme de santé connectée</p>
        <label>Identifiant
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required autoFocus />
        </label>
        <label>Mot de passe
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
        </label>
        <ErrorBox error={error} />
        <button className="btn" disabled={busy}>{busy ? 'Connexion…' : 'Se connecter'}</button>
        <p className="muted small">Comptes de démonstration : patient1, patient2, medecin, admin (mot de passe : variable DEMO_PASSWORD du fichier .env).</p>
      </form>
    </div>
  );
}
