-- Ambulances de démonstration (Grand Tunis)
INSERT INTO ambulance (id, statut, latitude, longitude) VALUES
  ('AMB-01', 'DISPONIBLE',      36.8442, 10.1964),
  ('AMB-02', 'DISPONIBLE',      36.7948, 10.1659),
  ('AMB-03', 'DISPONIBLE',      36.8065, 10.1815),
  ('AMB-04', 'HORS_SERVICE',    36.8190, 10.1658),
  ('AMB-05', 'EN_INTERVENTION', 36.8340, 10.2400)
ON CONFLICT (id) DO NOTHING;

-- Ordonnances de démonstration
INSERT INTO ordonnance (id, patient_id, doctor_id, date) VALUES
  ('ORD-2026-001230', 'PAT-000002', 'DOC-001', '2026-10-12'),
  ('ORD-2026-001118', 'PAT-000002', 'DOC-001', '2026-07-03'),
  ('ORD-2026-001301', 'PAT-000004', 'DOC-001', '2026-11-10'),
  ('ORD-2026-001302', 'PAT-000001', 'DOC-003', '2026-11-02'),
  ('ORD-2026-001303', 'PAT-000003', 'DOC-003', '2026-09-21')
ON CONFLICT (id) DO NOTHING;

INSERT INTO medicament (ordonnance_id, ordre, nom, posologie, duree_jours) VALUES
  ('ORD-2026-001230', 1, 'Furosémide 40 mg',  '1 comprimé le matin', 30),
  ('ORD-2026-001230', 2, 'Bisoprolol 5 mg',   '1 comprimé par jour', 90),
  ('ORD-2026-001118', 1, 'Ramipril 5 mg',     '1 comprimé le soir', 90),
  ('ORD-2026-001301', 1, 'Amiodarone 200 mg', '1 comprimé par jour, 5 jours sur 7', 60),
  ('ORD-2026-001301', 2, 'Acénocoumarol 4 mg', '1/2 comprimé le soir, selon INR', 30),
  ('ORD-2026-001302', 1, 'Amlodipine 5 mg',   '1 comprimé par jour', 90),
  ('ORD-2026-001303', 1, 'Vitamine D 100 000 UI', '1 ampoule par mois', 90)
ON CONFLICT (ordonnance_id, ordre) DO NOTHING;
