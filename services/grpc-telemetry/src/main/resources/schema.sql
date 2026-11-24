CREATE TABLE IF NOT EXISTS vital_sample (
    id            BIGSERIAL PRIMARY KEY,
    patient_id    VARCHAR(20)  NOT NULL,
    measured_at   TIMESTAMPTZ  NOT NULL,
    heart_rate    INT,
    resp_rate     INT,
    spo2          NUMERIC(4,1),
    systolic_bp   INT,
    diastolic_bp  INT,
    temperature   NUMERIC(3,1),
    on_oxygen     BOOLEAN,
    consciousness VARCHAR(20),
    fall_detected BOOLEAN,
    news2         INT
);
CREATE INDEX IF NOT EXISTS idx_vital_patient ON vital_sample (patient_id, measured_at DESC);

CREATE TABLE IF NOT EXISTS alert (
    id              UUID PRIMARY KEY,
    patient_id      VARCHAR(20) NOT NULL,
    raised_at       TIMESTAMPTZ NOT NULL,
    severity        VARCHAR(10) NOT NULL,
    news2_score     INT,
    reason          TEXT,
    trend_worsening BOOLEAN,
    status          VARCHAR(15) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_alert_patient ON alert (patient_id, raised_at DESC);
