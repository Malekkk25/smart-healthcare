'use strict';
/**
 * Stubs des services de B. Ils respectent les contrats contracts/dashboard.graphql et contracts/workflow-api.md.
 *   node server.js graphql        -> graphql-dashboard (8083) : requêtes + subscription alertCreated
 *   node server.js orchestrator   -> orchestrator (8085)      : /workflows, SSE
 * Les profils et ordonnances viennent du VRAI service REST ; les constantes vitales et les alertes sont simulées.
 * Ils sont remplacés par les vrais services de B (mêmes noms Docker, mêmes ports).
 */
const fs = require('fs');
const http = require('http');
const path = require('path');
const crypto = require('crypto');
const { on, EventEmitter } = require('events');
const { buildSchema, graphql } = require('graphql');
const { WebSocketServer } = require('ws');
const { useServer } = require('graphql-ws/lib/use/ws');

const mode = process.argv[2];
const REST_URL = process.env.REST_URL || 'http://rest-portal:8082';
const uuid = () => crypto.randomUUID();
const log = (...a) => console.log(new Date().toISOString(), `[stub-${mode}]`, ...a);

function json(res, status, body, extra = {}) {
  res.writeHead(status, { 'Content-Type': 'application/json', ...extra });
  res.end(JSON.stringify(body));
}
const erreur = (res, status, code, message, cid) =>
  json(res, status, { code, message, correlationId: cid || '' });

// ======================================================================= GraphQL

function startGraphql() {
  const sdlPath = process.env.SCHEMA_PATH || path.join(__dirname, 'dashboard.graphql');
  const schema = buildSchema(fs.readFileSync(sdlPath, 'utf8'));
  const ORDRE = { FAIBLE: 1, MOYENNE: 2, ELEVEE: 3, CRITIQUE: 4 };
  const bus = new EventEmitter();
  const alerts = [];

  async function rest(p) {
    const r = await fetch(REST_URL + p);
    if (!r.ok) throw new Error(`REST ${p} -> ${r.status}`);
    return r.json();
  }

  // Constantes simulées, stables par patient
  const hash = (s) => [...s].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7);
  function vitalPour(patientId, decalageMin = 0) {
    const h = hash(patientId);
    const t = new Date(Date.now() - decalageMin * 60000);
    return {
      measuredAt: t.toISOString(),
      heartRate: 62 + (h % 25) + (decalageMin % 3),
      respRate: 13 + (h % 5),
      spo2: 95 + (h % 5) - (decalageMin % 2) * 0.5,
      systolicBp: 112 + (h % 20),
      diastolicBp: 70 + (h % 12),
      temperature: 36.4 + (h % 6) / 10,
      onOxygen: false,
      consciousness: 'ALERT',
    };
  }

  function nouvelleAlerte(patientId, severity, score, reason, trend) {
    const a = {
      id: uuid(), patientId, raisedAt: new Date().toISOString(), severity, news2Score: score,
      reason, trendWorsening: trend, status: 'OPEN',
    };
    alerts.unshift(a);
    alerts.length = Math.min(alerts.length, 100);
    bus.emit('alert', a);
    log('alerte simulée', a.severity, a.patientId);
    return a;
  }

  const fields = (type) => schema.getType(type).getFields();
  fields('Query').patients.resolve = (_, { filtre }) => {
    const qs = new URLSearchParams();
    if (filtre?.service) qs.set('service', filtre.service);
    if (filtre?.pathologie) qs.set('pathologie', filtre.pathologie);
    if (filtre?.niveauRisque) qs.set('niveauRisque', filtre.niveauRisque);
    return rest('/patients?' + qs);
  };
  fields('Query').patient.resolve = (_, { id }) => rest('/patients/' + id).catch(() => null);
  fields('Query').alerts.resolve = (_, { patientId, status, limit }) =>
    alerts.filter((a) => (!patientId || a.patientId === patientId) && (!status || a.status === status)).slice(0, limit);
  fields('Patient').latestVital.resolve = (p) => vitalPour(p.id);
  fields('Patient').vitals.resolve = (p, { limit }) => Array.from({ length: limit }, (_, i) => vitalPour(p.id, i));
  fields('Patient').alerts.resolve = (p, { status }) =>
    alerts.filter((a) => a.patientId === p.id && (!status || a.status === status));
  fields('Patient').treatment.resolve = async (p) => ({ ordonnances: await rest(`/patients/${p.id}/prescriptions`) });
  fields('Alert').patient.resolve = (a) => rest('/patients/' + a.patientId).catch(() => null);
  fields('Subscription').alertCreated.subscribe = async function* (_, { minSeverity }) {
    for await (const [alerte] of on(bus, 'alert')) {
      if (ORDRE[alerte.severity] >= ORDRE[minSeverity]) yield { alertCreated: alerte };
    }
  };

  const server = http.createServer(async (req, res) => {
    const cid = req.headers['x-correlation-id'] || uuid();
    if (req.url === '/actuator/health') return json(res, 200, { status: 'UP' });
    if (req.url.startsWith('/graphql') && req.method === 'POST') {
      let corps = '';
      for await (const c of req) corps += c;
      try {
        const { query, variables, operationName } = JSON.parse(corps);
        const result = await graphql({ schema, source: query, variableValues: variables, operationName });
        return json(res, 200, result, { 'X-Correlation-Id': cid });
      } catch (e) {
        return erreur(res, 400, 'REQUETE_INVALIDE', 'Requête GraphQL invalide.', cid);
      }
    }
    return erreur(res, 404, 'RESSOURCE_INTROUVABLE', 'Route inconnue.', cid);
  });

  const wss = new WebSocketServer({ server, path: '/graphql' });
  useServer({ schema }, wss);

  // Alertes simulées périodiques (toutes les STUB_ALERT_INTERVAL secondes, 0 = désactivé)
  const cadence = Number(process.env.STUB_ALERT_INTERVAL ?? 30);
  if (cadence > 0) {
    const scenarios = [
      ['PAT-000004', 'CRITIQUE', 8, 'NEWS2 = 8 (SpO2 90 %, pouls 148)', false],
      ['PAT-000002', 'ELEVEE', 6, 'NEWS2 = 6 (FR 25, SpO2 93 %)', true],
      ['PAT-000012', 'MOYENNE', 4, 'chute détectée, conscience : confusion', false],
    ];
    let i = 0;
    setInterval(() => nouvelleAlerte(...scenarios[i++ % scenarios.length]), cadence * 1000);
  }
  nouvelleAlerte('PAT-000004', 'ELEVEE', 6, 'NEWS2 = 6 (alerte de départ du stub)', true);

  server.listen(8083, () => log('GraphQL sur :8083/graphql (HTTP + WebSocket), REST =', REST_URL));
}

// ======================================================================= Orchestrateur

function startOrchestrator() {
  const workflows = new Map();
  const clientsGlobaux = new Set();
  const clientsParWorkflow = new Map();
  const NOMS = ['ALERTE', 'DROITS', 'PRISE_EN_CHARGE', 'FICHE'];

  const diffuser = (workflowId, event, data) => {
    const msg = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
    clientsGlobaux.forEach((r) => r.write(msg));
    (clientsParWorkflow.get(workflowId) || new Set()).forEach((r) => r.write(msg));
  };
  const attendre = (ms) => new Promise((r) => setTimeout(r, ms));

  async function executer(w, { degrade = false, echecPriseEnCharge = false } = {}) {
    diffuser(w.id, 'WORKFLOW_STARTED', w);
    const details = {
      ALERTE: 'NEWS2 = 8',
      DROITS: degrade ? 'SOAP indisponible : vérification différée' : 'droits ouverts',
      PRISE_EN_CHARGE: echecPriseEnCharge ? '3 essais : créneau d\'urgence réservé' : 'AMB-03, ETA 7 min',
      FICHE: 'fiche envoyée au médecin',
    };
    for (const step of w.steps) {
      step.status = 'RUNNING'; step.attempts = 1;
      diffuser(w.id, 'STEP_UPDATED', { workflowId: w.id, step });
      await attendre(1200);
      const repli = (step.name === 'DROITS' && degrade) || (step.name === 'PRISE_EN_CHARGE' && echecPriseEnCharge);
      step.status = repli ? 'DEGRADED' : 'SUCCEEDED';
      step.attempts = repli ? 3 : 1;
      step.detail = details[step.name];
      diffuser(w.id, 'STEP_UPDATED', { workflowId: w.id, step });
    }
    if (echecPriseEnCharge) {
      w.adminAlert = 'Aucune ambulance : créneau d\'urgence réservé, intervention manuelle requise';
      diffuser(w.id, 'ADMIN_ALERT', { workflowId: w.id, message: w.adminAlert });
    }
    w.status = degrade || echecPriseEnCharge ? 'DEGRADED' : 'COMPLETED';
    w.endedAt = new Date().toISOString();
    diffuser(w.id, 'WORKFLOW_ENDED', w);
  }

  function creer(alertId, patientId, options) {
    for (const w of workflows.values()) if (w.alertId === alertId) return { w, existant: true };
    const w = {
      id: uuid(), alertId, patientId, correlationId: uuid(), status: 'RUNNING',
      startedAt: new Date().toISOString(), endedAt: null, adminAlert: null,
      steps: NOMS.map((name) => ({ name, status: 'PENDING', attempts: 0, detail: null })),
    };
    workflows.set(w.id, w);
    executer(w, options);
    return { w, existant: false };
  }

  const sse = (req, res, set) => {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
    res.write(': connecté\n\n');
    set.add(res);
    const keep = setInterval(() => res.write(': keepalive\n\n'), 15000);
    req.on('close', () => { clearInterval(keep); set.delete(res); });
  };

  const server = http.createServer(async (req, res) => {
    const cid = req.headers['x-correlation-id'] || uuid();
    const url = new URL(req.url, 'http://x');
    const p = url.pathname;
    if (p === '/actuator/health') return json(res, 200, { status: 'UP' });

    if (p === '/workflows/events' && req.method === 'GET') return sse(req, res, clientsGlobaux);
    let m = p.match(/^\/workflows\/([^/]+)\/events$/);
    if (m && req.method === 'GET') {
      if (!workflows.has(m[1])) return erreur(res, 404, 'WORKFLOW_INTROUVABLE', 'Intervention introuvable.', cid);
      if (!clientsParWorkflow.has(m[1])) clientsParWorkflow.set(m[1], new Set());
      return sse(req, res, clientsParWorkflow.get(m[1]));
    }
    m = p.match(/^\/workflows\/([^/]+)$/);
    if (m && req.method === 'GET') {
      const w = workflows.get(m[1]);
      return w ? json(res, 200, w) : erreur(res, 404, 'WORKFLOW_INTROUVABLE', 'Intervention introuvable.', cid);
    }
    if (p === '/workflows' && req.method === 'GET') {
      const status = url.searchParams.get('status');
      const patientId = url.searchParams.get('patientId');
      const limit = Number(url.searchParams.get('limit') || 50);
      const liste = [...workflows.values()].reverse()
        .filter((w) => (!status || w.status === status) && (!patientId || w.patientId === patientId)).slice(0, limit);
      return json(res, 200, liste);
    }
    if (p === '/workflows' && req.method === 'POST') {
      let corps = '';
      for await (const c of req) corps += c;
      let body;
      try { body = JSON.parse(corps); } catch { body = null; }
      if (!body?.alertId || !/^PAT-\d{6}$/.test(body?.patientId || '')) {
        return erreur(res, 400, 'REQUETE_INVALIDE', "'alertId' et 'patientId' (PAT-000123) sont obligatoires.", cid);
      }
      const { w, existant } = creer(body.alertId, body.patientId, { degrade: url.searchParams.get('degrade') === 'true' });
      return json(res, existant ? 200 : 202, w);
    }
    return erreur(res, 404, 'RESSOURCE_INTROUVABLE', 'Route inconnue.', cid);
  });

  // Interventions simulées périodiques (STUB_WORKFLOW_INTERVAL secondes, 0 = désactivé)
  const cadence = Number(process.env.STUB_WORKFLOW_INTERVAL ?? 45);
  const patients = ['PAT-000004', 'PAT-000002', 'PAT-000012', 'PAT-000019'];
  let n = 0;
  creer(uuid(), 'PAT-000004', {});
  if (cadence > 0) {
    setInterval(() => {
      n += 1;
      creer(uuid(), patients[n % patients.length], { degrade: n % 3 === 0, echecPriseEnCharge: n % 5 === 0 });
    }, cadence * 1000);
  }
  server.listen(8085, () => log('API workflows sur :8085'));
}

if (mode === 'graphql') startGraphql();
else if (mode === 'orchestrator') startOrchestrator();
else { console.error('usage : node server.js graphql|orchestrator'); process.exit(1); }
