CREATE TABLE IF NOT EXISTS patient (
    id                 VARCHAR(20)  PRIMARY KEY,
    nom                VARCHAR(100) NOT NULL,
    prenom             VARCHAR(100) NOT NULL,
    numero_assure      VARCHAR(20)  NOT NULL,
    taux_remboursement INT          NOT NULL CHECK (taux_remboursement IN (50, 80)),
    date_fin_droits    DATE         NOT NULL
);

-- Nomenclature fictive des actes cotés en lettres-clés
CREATE TABLE IF NOT EXISTS acte_nomenclature (
    code       VARCHAR(4)    PRIMARY KEY,
    libelle    VARCHAR(100)  NOT NULL,
    tarif_base NUMERIC(10,3) NOT NULL
);

CREATE TABLE IF NOT EXISTS feuille_soins (
    numero             VARCHAR(30)   PRIMARY KEY,
    patient_id         VARCHAR(20)   NOT NULL REFERENCES patient (id),
    code_acte          VARCHAR(4)    NOT NULL,
    date_acte          DATE          NOT NULL,
    montant            NUMERIC(10,3) NOT NULL,
    praticien_id       VARCHAR(20)   NOT NULL,
    montant_rembourse  NUMERIC(10,3) NOT NULL,
    statut             VARCHAR(20)   NOT NULL,
    emise_le           TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE SEQUENCE IF NOT EXISTS feuille_seq START 1;

CREATE TABLE IF NOT EXISTS prise_en_charge (
    id         VARCHAR(30)  NOT NULL,
    patient_id VARCHAR(20)  NOT NULL REFERENCES patient (id),
    garantie   VARCHAR(100) NOT NULL,
    valeur     VARCHAR(100) NOT NULL,
    PRIMARY KEY (id, garantie)
);
