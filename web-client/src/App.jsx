import { Navigate, NavLink, Outlet, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth.jsx';
import Login from './pages/Login.jsx';
import RendezVous from './pages/RendezVous.jsx';
import Ordonnances from './pages/Ordonnances.jsx';
import Profil from './pages/Profil.jsx';
import Droits from './pages/Droits.jsx';
import Patients from './pages/Patients.jsx';
import FichePatient from './pages/FichePatient.jsx';
import Alertes from './pages/Alertes.jsx';
import Creneaux from './pages/Creneaux.jsx';
import Ambulances from './pages/Ambulances.jsx';
import Interventions from './pages/Interventions.jsx';
import Carte from './pages/Carte.jsx';

const NAV = {
  PATIENT: [
    ['/rendez-vous', 'Mes rendez-vous'],
    ['/ordonnances', 'Mes ordonnances'],
    ['/profil', 'Mon profil'],
    ['/droits', 'Mes droits CNAM'],
  ],
  MEDECIN: [
    ['/patients', 'Patients'],
    ['/alertes', 'Alertes en direct'],
    ['/interventions', 'Interventions'],
    ['/carte', 'Carte'],
    ['/droits', 'Droits CNAM'],
  ],
  ADMIN: [
    ['/ambulances', 'Ambulances et urgences'],
    ['/creneaux', 'Créneaux'],
    ['/interventions', 'Interventions'],
    ['/carte', 'Carte'],
    ['/patients', 'Patients'],
    ['/droits', 'Droits CNAM'],
  ],
};

const HOME = { PATIENT: '/rendez-vous', MEDECIN: '/patients', ADMIN: '/ambulances' };

function Layout() {
  const { session, logout } = useAuth();
  return (
    <div className="app">
      <header className="topbar">
        <span className="brand">Smart Healthcare</span>
        <nav>
          {NAV[session.role].map(([to, label]) => (
            <NavLink key={to} to={to} className={({ isActive }) => (isActive ? 'active' : '')}>{label}</NavLink>
          ))}
        </nav>
        <div className="user">
          <span>{session.user} <span className="badge badge-muted">{session.role.toLowerCase()}</span></span>
          <button className="btn btn-ghost" onClick={logout}>Déconnexion</button>
        </div>
      </header>
      <main className="content"><Outlet /></main>
    </div>
  );
}

/** Autorise l'accès seulement aux rôles listés. */
function Protected({ roles, children }) {
  const { session } = useAuth();
  if (!session) return <Navigate to="/connexion" replace />;
  if (roles && !roles.includes(session.role)) return <Navigate to={HOME[session.role]} replace />;
  return children;
}

export default function App() {
  const { session } = useAuth();
  const staff = ['MEDECIN', 'ADMIN'];
  return (
    <Routes>
      <Route path="/connexion" element={session ? <Navigate to={HOME[session.role]} replace /> : <Login />} />
      <Route element={<Protected><Layout /></Protected>}>
        <Route path="/" element={session ? <Navigate to={HOME[session.role]} replace /> : null} />
        <Route path="/rendez-vous" element={<Protected roles={['PATIENT']}><RendezVous /></Protected>} />
        <Route path="/ordonnances" element={<Protected roles={['PATIENT']}><Ordonnances /></Protected>} />
        <Route path="/profil" element={<Protected roles={['PATIENT']}><Profil /></Protected>} />
        <Route path="/droits" element={<Droits />} />
        <Route path="/patients" element={<Protected roles={staff}><Patients /></Protected>} />
        <Route path="/patients/:id" element={<Protected roles={staff}><FichePatient /></Protected>} />
        <Route path="/alertes" element={<Protected roles={['MEDECIN']}><Alertes /></Protected>} />
        <Route path="/ambulances" element={<Protected roles={['ADMIN']}><Ambulances /></Protected>} />
        <Route path="/creneaux" element={<Protected roles={['ADMIN']}><Creneaux /></Protected>} />
        <Route path="/interventions" element={<Protected roles={staff}><Interventions /></Protected>} />
        <Route path="/carte" element={<Protected roles={staff}><Carte /></Protected>} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
