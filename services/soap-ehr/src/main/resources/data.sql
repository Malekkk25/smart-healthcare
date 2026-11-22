-- Nomenclature fictive (lettres-clés)
INSERT INTO acte_nomenclature (code, libelle, tarif_base) VALUES
  ('C',  'Consultation médecin généraliste', 25.000),
  ('CS', 'Consultation médecin spécialiste', 35.000),
  ('K',  'Acte de chirurgie ou de spécialité', 120.000),
  ('KC', 'Acte de chirurgie cardiaque', 450.000),
  ('Z',  'Imagerie / radiologie', 60.000),
  ('B',  'Analyse de biologie médicale', 18.000)
ON CONFLICT (code) DO NOTHING;

-- Historique d'actes certifiés (PAT-000002 et PAT-000004 correspondent aux exemples du contrat)
INSERT INTO feuille_soins (numero, patient_id, code_acte, date_acte, montant, praticien_id, montant_rembourse, statut) VALUES
  ('FS-2026-900001', 'PAT-000002', 'CS', '2026-11-20', 35.000, 'DOC-001', 28.000, 'EMISE'),
  ('FS-2026-900002', 'PAT-000002', 'K',  '2026-10-05', 120.000, 'DOC-002', 96.000, 'EMISE'),
  ('FS-2026-900003', 'PAT-000004', 'CS', '2026-11-10', 35.000, 'DOC-001', 17.500, 'EMISE'),
  ('FS-2026-900004', 'PAT-000004', 'B',  '2026-09-18', 18.000, 'DOC-003', 9.000, 'EMISE')
ON CONFLICT (numero) DO NOTHING;

-- Prises en charge comparables (comparerGaranties)
INSERT INTO prise_en_charge (id, patient_id, garantie, valeur) VALUES
  ('PEC-2025-001', 'PAT-000002', 'Consultation généraliste', '80%'),
  ('PEC-2025-001', 'PAT-000002', 'Consultation spécialiste', '50%'),
  ('PEC-2025-001', 'PAT-000002', 'Hospitalisation', '80% (plafond 60 jours)'),
  ('PEC-2025-001', 'PAT-000002', 'Optique', 'non couvert'),
  ('PEC-2026-007', 'PAT-000002', 'Consultation généraliste', '80%'),
  ('PEC-2026-007', 'PAT-000002', 'Consultation spécialiste', '80%'),
  ('PEC-2026-007', 'PAT-000002', 'Hospitalisation', '80% (plafond 90 jours)'),
  ('PEC-2026-007', 'PAT-000002', 'Optique', '50%'),
  ('PEC-2025-014', 'PAT-000004', 'Consultation généraliste', '50%'),
  ('PEC-2025-014', 'PAT-000004', 'Consultation spécialiste', '50%'),
  ('PEC-2026-021', 'PAT-000004', 'Consultation généraliste', '50%'),
  ('PEC-2026-021', 'PAT-000004', 'Consultation spécialiste', '80%'),
  ('PEC-2026-021', 'PAT-000004', 'Transport sanitaire', '100%')
ON CONFLICT (id, garantie) DO NOTHING;
