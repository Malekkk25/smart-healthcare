CREATE TABLE IF NOT EXISTS patient (
    id                 VARCHAR(20)  PRIMARY KEY,
    nom                VARCHAR(100) NOT NULL,
    prenom             VARCHAR(100) NOT NULL,
    sexe               CHAR(1)      NOT NULL CHECK (sexe IN ('M', 'F')),
    date_naissance     DATE         NOT NULL,
    service            VARCHAR(50),
    pathologie         VARCHAR(100),
    niveau_risque      VARCHAR(10),
    numero_assure      VARCHAR(20),
    taux_remboursement INT
);

CREATE TABLE IF NOT EXISTS creneau (
    id        VARCHAR(40) PRIMARY KEY,
    doctor_id VARCHAR(20) NOT NULL,
    debut     TIMESTAMPTZ NOT NULL,
    fin       TIMESTAMPTZ NOT NULL,
    urgence   BOOLEAN     NOT NULL DEFAULT false,
    reserve   BOOLEAN     NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS idx_creneau_recherche ON creneau (doctor_id, debut);

CREATE SEQUENCE IF NOT EXISTS rdv_seq START 1;

CREATE TABLE IF NOT EXISTS rendez_vous (
    id         VARCHAR(30) PRIMARY KEY,
    patient_id VARCHAR(20) NOT NULL REFERENCES patient (id),
    slot_id    VARCHAR(40) NOT NULL REFERENCES creneau (id),
    motif      VARCHAR(255),
    statut     VARCHAR(10) NOT NULL CHECK (statut IN ('CONFIRME', 'ANNULE', 'TERMINE'))
);
CREATE INDEX IF NOT EXISTS idx_rdv_patient ON rendez_vous (patient_id);

CREATE TABLE IF NOT EXISTS ordonnance (
    id         VARCHAR(30) PRIMARY KEY,
    patient_id VARCHAR(20) NOT NULL REFERENCES patient (id),
    doctor_id  VARCHAR(20) NOT NULL,
    date       DATE        NOT NULL
);

CREATE TABLE IF NOT EXISTS medicament (
    ordonnance_id VARCHAR(30)  NOT NULL REFERENCES ordonnance (id),
    ordre         INT          NOT NULL,
    nom           VARCHAR(100) NOT NULL,
    posologie     VARCHAR(200) NOT NULL,
    duree_jours   INT,
    PRIMARY KEY (ordonnance_id, ordre)
);

CREATE TABLE IF NOT EXISTS ambulance (
    id              VARCHAR(20)      PRIMARY KEY,
    statut          VARCHAR(20)      NOT NULL CHECK (statut IN ('DISPONIBLE', 'EN_INTERVENTION', 'HORS_SERVICE')),
    latitude        DOUBLE PRECISION NOT NULL,
    longitude       DOUBLE PRECISION NOT NULL,
    occupee_jusqua  TIMESTAMPTZ
);

CREATE SEQUENCE IF NOT EXISTS dispatch_seq START 1;

CREATE TABLE IF NOT EXISTS dispatch (
    id                      VARCHAR(30)      PRIMARY KEY,
    alert_id                VARCHAR(64)      NOT NULL UNIQUE,
    patient_id              VARCHAR(20)      NOT NULL REFERENCES patient (id),
    severity                VARCHAR(10)      NOT NULL,
    latitude                DOUBLE PRECISION NOT NULL,
    longitude               DOUBLE PRECISION NOT NULL,
    ambulance_id            VARCHAR(20),
    eta_minutes             INT,
    fallback_appointment_id VARCHAR(30),
    created_at              TIMESTAMPTZ      NOT NULL DEFAULT now()
);
