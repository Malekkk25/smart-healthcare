// Client HTTP : tout passe par la Gateway (jamais directement par un service).
const BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080';

let token = null;
let onUnauthorized = () => {};

export const setToken = (t) => { token = t; };
export const setUnauthorizedHandler = (fn) => { onUnauthorized = fn; };

export class ApiError extends Error {
  constructor(status, code, message, correlationId) {
    super(message);
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
  }
}

const newCorrelationId = () =>
  (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`);

/** Appel JSON. Un X-Correlation-Id est généré pour chaque requête. */
export async function api(path, { method = 'GET', body, auth = true } = {}) {
  const correlationId = newCorrelationId();
  const headers = { 'X-Correlation-Id': correlationId };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth && token) headers.Authorization = `Bearer ${token}`;

  let res;
  try {
    res = await fetch(BASE + path, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined });
  } catch {
    throw new ApiError(0, 'RESEAU', 'Impossible de joindre la Gateway. Vérifiez que les services sont démarrés.', correlationId);
  }
  if (res.status === 204) return null;

  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* corps non JSON */ }

  if (!res.ok) {
    if (res.status === 401 && auth) onUnauthorized();
    throw new ApiError(
      res.status,
      data?.code || 'ERREUR',
      data?.message || `Erreur ${res.status}`,
      data?.correlationId || res.headers.get('X-Correlation-Id') || correlationId,
    );
  }
  return data;
}

// ---------------------------------------------------------------- SOAP (le client construit l'enveloppe)

const EHR_NS = 'http://smarthealthcare.tn/ehr';
const esc = (v) => String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

export class SoapFault extends Error {
  constructor(faultString, type, detail) {
    super(faultString);
    this.type = type; // PatientInconnuFault, DroitsExpiresFault, ... (nom de l'élément de détail)
    this.detail = detail; // { champ: valeur }
  }
}

/**
 * Appelle une opération du service EHR (document/literal) via /api/ehr.
 * @param operation nom de l'opération (sert aussi au SOAPAction)
 * @param fields    {champ: valeur} des éléments enfants de <operation>Request
 * @returns {Object} éléments de la réponse sous forme d'objet (les listes répétées deviennent des tableaux)
 */
export async function soapCall(operation, fields) {
  const inner = Object.entries(fields).map(([k, v]) => `<ehr:${k}>${esc(v)}</ehr:${k}>`).join('');
  const xml = `<?xml version="1.0" encoding="UTF-8"?>`
    + `<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ehr="${EHR_NS}">`
    + `<soapenv:Body><ehr:${operation}Request>${inner}</ehr:${operation}Request></soapenv:Body></soapenv:Envelope>`;

  const correlationId = newCorrelationId();
  const headers = {
    'Content-Type': 'text/xml; charset=utf-8',
    SOAPAction: `${EHR_NS}/${operation}`,
    'X-Correlation-Id': correlationId,
  };
  if (token) headers.Authorization = `Bearer ${token}`;

  let res;
  try {
    res = await fetch(`${BASE}/api/ehr`, { method: 'POST', headers, body: xml });
  } catch {
    throw new ApiError(0, 'RESEAU', 'Impossible de joindre la Gateway.', correlationId);
  }
  const text = await res.text();
  const contentType = res.headers.get('Content-Type') || '';

  // Erreur renvoyée par la Gateway (JSON) : 401, 403, 503...
  if (contentType.includes('json')) {
    let data = {};
    try { data = JSON.parse(text); } catch { /* ignore */ }
    if (res.status === 401) onUnauthorized();
    throw new ApiError(res.status, data.code || 'ERREUR', data.message || `Erreur ${res.status}`, data.correlationId);
  }

  const doc = new DOMParser().parseFromString(text, 'text/xml');
  const local = (el) => el.localName;
  const fault = [...doc.getElementsByTagName('*')].find((e) => local(e) === 'Fault');
  if (fault) {
    const child = (parent, name) => [...parent.children].find((c) => local(c) === name);
    const faultString = child(fault, 'faultstring')?.textContent || 'Erreur SOAP';
    const detailEl = child(fault, 'detail');
    const detailRoot = detailEl ? detailEl.children[0] : null;
    const detail = {};
    if (detailRoot) [...detailRoot.children].forEach((c) => { detail[local(c)] = c.textContent; });
    throw new SoapFault(faultString, detailRoot ? local(detailRoot) : null, detail);
  }

  const body = [...doc.getElementsByTagName('*')].find((e) => local(e) === 'Body');
  const root = body?.children[0];
  if (!root) throw new ApiError(res.status, 'REPONSE_INVALIDE', 'Réponse SOAP illisible.', correlationId);
  return xmlToObject(root);
}

function xmlToObject(el) {
  const out = {};
  for (const c of el.children) {
    const key = c.localName;
    const value = c.children.length ? xmlToObject(c) : c.textContent;
    if (key in out) {
      out[key] = Array.isArray(out[key]) ? [...out[key], value] : [out[key], value];
    } else {
      out[key] = value;
    }
  }
  return out;
}

/** Les éléments répétés d'une réponse SOAP : 0 → [], 1 → [x], n → tableau. */
export const toList = (v) => (v === undefined ? [] : Array.isArray(v) ? v : [v]);

// ---------------------------------------------------------------- GraphQL (service de B, via /api/dashboard)

/** Exécute une requête GraphQL. Les erreurs partielles (errors[] avec data) sont renvoyées dans `errors`. */
export async function graphql(query, variables = {}) {
  const res = await api('/api/dashboard/graphql', { method: 'POST', body: { query, variables } });
  if (!res.data && res.errors?.length) {
    throw new ApiError(200, 'GRAPHQL', res.errors.map((e) => e.message).join(' ; '));
  }
  return { data: res.data, errors: res.errors || [] };
}

/**
 * Abonnement GraphQL (protocole graphql-transport-ws) par WebSocket.
 * Le navigateur ne peut pas envoyer d'en-tête Authorization : le jeton passe dans ?access_token=.
 * Se reconnecte automatiquement. Renvoie une fonction d'arrêt.
 */
export function subscribe(query, variables, { onData, onStatus }) {
  const url = `${BASE.replace(/^http/, 'ws')}/api/dashboard/graphql?access_token=${encodeURIComponent(token || '')}`;
  let ws;
  let arret = false;
  let essai = 0;
  let timer;

  const ouvrir = () => {
    onStatus?.(essai === 0 ? 'connexion' : 'reconnexion');
    ws = new WebSocket(url, 'graphql-transport-ws');
    ws.onopen = () => ws.send(JSON.stringify({ type: 'connection_init' }));
    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.type === 'connection_ack') {
        essai = 0;
        onStatus?.('connecté');
        ws.send(JSON.stringify({ id: '1', type: 'subscribe', payload: { query, variables } }));
      } else if (msg.type === 'next') {
        if (msg.payload?.data) onData(msg.payload.data);
      } else if (msg.type === 'ping') {
        ws.send(JSON.stringify({ type: 'pong' }));
      } else if (msg.type === 'error') {
        onStatus?.('erreur');
      }
    };
    ws.onclose = () => {
      if (arret) return;
      onStatus?.('déconnecté');
      essai += 1;
      timer = setTimeout(ouvrir, Math.min(1000 * 2 ** essai, 15000));
    };
  };
  ouvrir();
  return () => { arret = true; clearTimeout(timer); ws?.close(); };
}

// ---------------------------------------------------------------- SSE (orchestrateur de B, via /api/orchestrator)

/**
 * Lit un flux text/event-stream avec fetch (EventSource ne permet pas l'en-tête Authorization).
 * Appelle onEvent(event, data) pour chaque événement ; se reconnecte automatiquement.
 */
export function streamEvents(path, { onEvent, onStatus }) {
  const controller = new AbortController();
  let arret = false;
  let essai = 0;

  const boucle = async () => {
    while (!arret) {
      try {
        onStatus?.(essai === 0 ? 'connexion' : 'reconnexion');
        const res = await fetch(BASE + path, {
          headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream', 'X-Correlation-Id': newCorrelationId() },
          signal: controller.signal,
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        onStatus?.('connecté');
        essai = 0;
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let tampon = '';
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          tampon += decoder.decode(value, { stream: true });
          let i;
          while ((i = tampon.indexOf('\n\n')) >= 0) {
            const bloc = tampon.slice(0, i);
            tampon = tampon.slice(i + 2);
            let event = 'message';
            let data = '';
            for (const ligne of bloc.split('\n')) {
              if (ligne.startsWith('event:')) event = ligne.slice(6).trim();
              else if (ligne.startsWith('data:')) data += ligne.slice(5).trim();
            }
            if (data) { try { onEvent(event, JSON.parse(data)); } catch { /* événement illisible */ } }
          }
        }
      } catch (e) {
        if (arret) return;
      }
      if (arret) return;
      onStatus?.('déconnecté');
      essai += 1;
      await new Promise((r) => setTimeout(r, Math.min(1000 * 2 ** essai, 15000)));
    }
  };
  boucle();
  return () => { arret = true; controller.abort(); };
}
