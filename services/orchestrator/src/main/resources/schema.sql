CREATE TABLE IF NOT EXISTS workflow (
    id              VARCHAR(36)  PRIMARY KEY,
    alert_id        VARCHAR(64)  NOT NULL UNIQUE,   -- idempotence : une seule intervention par alerte
    patient_id      VARCHAR(20)  NOT NULL,
    correlation_id  VARCHAR(64)  NOT NULL,
    status          VARCHAR(10)  NOT NULL,
    started_at      TIMESTAMPTZ  NOT NULL,
    ended_at        TIMESTAMPTZ,
    admin_alert     TEXT,
    severity        VARCHAR(10),
    news2_score     INT,
    reason          TEXT,
    dispatch_id     VARCHAR(30),
    ambulance_id    VARCHAR(20),
    eta_minutes     INT,
    appointment_id  VARCHAR(30),
    dossier         TEXT
);
CREATE INDEX IF NOT EXISTS idx_workflow_patient ON workflow (patient_id, started_at DESC);

CREATE TABLE IF NOT EXISTS workflow_step (
    workflow_id VARCHAR(36)  NOT NULL REFERENCES workflow (id),
    name        VARCHAR(20)  NOT NULL,
    ordre       INT          NOT NULL,
    status      VARCHAR(10)  NOT NULL,
    attempts    INT          NOT NULL DEFAULT 0,
    detail      TEXT,
    PRIMARY KEY (workflow_id, name)
);

-- Journal d'exécution : une ligne par changement d'état ou tentative
CREATE TABLE IF NOT EXISTS step_log (
    id          BIGSERIAL PRIMARY KEY,
    workflow_id VARCHAR(36) NOT NULL,
    step        VARCHAR(20) NOT NULL,
    status      VARCHAR(10) NOT NULL,
    attempt     INT         NOT NULL,
    detail      TEXT,
    at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_step_log_workflow ON step_log (workflow_id, at);

-- Tâches à rejouer : vérification de droits différée (SOAP en panne), fiche différée (GraphQL en panne)
CREATE TABLE IF NOT EXISTS pending_task (
    id          BIGSERIAL PRIMARY KEY,
    workflow_id VARCHAR(36) NOT NULL,
    kind        VARCHAR(20) NOT NULL,
    attempts    INT         NOT NULL DEFAULT 0,
    next_run    TIMESTAMPTZ NOT NULL,
    status      VARCHAR(10) NOT NULL DEFAULT 'PENDING'
);
CREATE INDEX IF NOT EXISTS idx_pending_due ON pending_task (status, next_run);
