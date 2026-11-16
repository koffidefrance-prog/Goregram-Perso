import 'dotenv/config';
import http from 'node:http';
import crypto from 'node:crypto';
import express from 'express';
import helmet from 'helmet';
import cors from 'cors';
import rateLimit from 'express-rate-limit';
import jwt from 'jsonwebtoken';
import pg from 'pg';
import { WebSocketServer } from 'ws';
import { isPhoneE164, cleanMessage, isValidMessage } from './validation.js';

const { Pool } = pg;
const app = express();
const server = http.createServer(app);
const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: process.env.PGSSL === 'true' ? { rejectUnauthorized: true } : undefined,
  max: 5, idleTimeoutMillis: 30000, connectionTimeoutMillis: 10000
});
const production = process.env.NODE_ENV === 'production';
const jwtSecret = process.env.JWT_SECRET || '';
const otpSecret = process.env.OTP_HASH_SECRET || '';
if (production && (jwtSecret.length < 32 || otpSecret.length < 32)) {
  throw new Error('JWT_SECRET and OTP_HASH_SECRET must each contain at least 32 characters.');
}
app.disable('x-powered-by');
app.use(helmet());
app.use(cors({ origin: process.env.CORS_ORIGIN || false }));
app.use(express.json({ limit: '32kb' }));
app.use('/auth/', rateLimit({ windowMs: 15 * 60 * 1000, limit: 8, standardHeaders: true, legacyHeaders: false }));
app.use('/users/', rateLimit({ windowMs: 60 * 1000, limit: 60, standardHeaders: true, legacyHeaders: false }));

const sign = user => jwt.sign({ sub: String(user.id), phone: user.phone_e164 }, jwtSecret || 'local-development-secret-change-me', { expiresIn: '30d', issuer: 'goregram-api' });
function auth(req, res, next) {
  try {
    const token = (req.headers.authorization || '').replace(/^Bearer /, '');
    req.user = jwt.verify(token, jwtSecret || 'local-development-secret-change-me', { issuer: 'goregram-api' });
    next();
  } catch { res.status(401).json({ error: 'UNAUTHORIZED' }); }
}
const hashOtp = (phone, code) => crypto.createHmac('sha256', otpSecret || 'local-dev-only').update(phone + ':' + code).digest('hex');
async function sendSms(phone, code) {
  const { TWILIO_ACCOUNT_SID: sid, TWILIO_AUTH_TOKEN: token, TWILIO_FROM_NUMBER: from } = process.env;
  if (!sid || !token || !from) {
    if (!production) { console.warn('[DEV ONLY] OTP for ' + phone + ': ' + code); return; }
    throw new Error('SMS_PROVIDER_NOT_CONFIGURED');
  }
  const body = new URLSearchParams({ To: phone, From: from, Body: 'Votre code Goregram Perso : ' + code + '. Valable 5 minutes.' });
  const response = await fetch('https://api.twilio.com/2010-04-01/Accounts/' + sid + '/Messages.json', {
    method: 'POST',
    headers: { Authorization: 'Basic ' + Buffer.from(sid + ':' + token).toString('base64'), 'Content-Type': 'application/x-www-form-urlencoded' },
    body
  });
  if (!response.ok) throw new Error('SMS_SEND_FAILED');
}
app.get('/health', async (_req, res) => {
  try { await pool.query('SELECT 1'); res.json({ ok: true, service: 'goregram-api', version: '1.0.0' }); }
  catch { res.status(503).json({ ok: false, error: 'DATABASE_UNAVAILABLE' }); }
});
app.post('/auth/request-otp', async (req, res) => {
  const phone = String(req.body?.phone || '');
  if (!isPhoneE164(phone)) return res.status(400).json({ error: 'PHONE_MUST_BE_E164' });
  const code = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
  const ttl = Math.min(Math.max(Number(process.env.OTP_TTL_SECONDS || 300), 60), 600);
  try {
    await sendSms(phone, code);
    await pool.query(\`INSERT INTO phone_otps(phone_e164, code_hash, expires_at, attempts)
      VALUES($1,$2,NOW()+($3 || ' seconds')::interval,0)
      ON CONFLICT(phone_e164) DO UPDATE SET code_hash=EXCLUDED.code_hash, expires_at=EXCLUDED.expires_at, attempts=0, created_at=NOW()\`,
      [phone, hashOtp(phone, code), String(ttl)]);
    res.json({ ok: true, expires_in: ttl });
  } catch (e) {
    console.error('OTP request failed:', e.message);
    res.status(503).json({ error: 'SMS_UNAVAILABLE', message: 'Le service SMS doit être configuré avant les inscriptions.' });
  }
});
app.post('/auth/verify-otp', async (req, res) => {
  const phone = String(req.body?.phone || ''), code = String(req.body?.code || '');
  if (!isPhoneE164(phone) || !/^\d{6}$/.test(code)) return res.status(400).json({ error: 'INVALID_INPUT' });
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await client.query('SELECT * FROM phone_otps WHERE phone_e164=$1 FOR UPDATE', [phone]);
    const row = result.rows[0];
    if (!row || new Date(row.expires_at) < new Date() || row.attempts >= 5) {
      await client.query('DELETE FROM phone_otps WHERE phone_e164=$1', [phone]);
      await client.query('COMMIT');
      return res.status(400).json({ error: 'OTP_EXPIRED_OR_INVALID' });
    }
    if (hashOtp(phone, code) !== row.code_hash) {
      await client.query('UPDATE phone_otps SET attempts=attempts+1 WHERE phone_e164=$1', [phone]);
      await client.query('COMMIT');
      return res.status(400).json({ error: 'OTP_EXPIRED_OR_INVALID' });
    }
    await client.query('DELETE FROM phone_otps WHERE phone_e164=$1', [phone]);
    const userResult = await client.query(\`INSERT INTO users(phone_e164, display_name, created_at, updated_at, last_seen_at)
      VALUES($1, 'Utilisateur', NOW(), NOW(), NOW())
      ON CONFLICT(phone_e164) DO UPDATE SET last_seen_at=NOW(), updated_at=NOW()
      RETURNING id, phone_e164, display_name, avatar_url\`, [phone]);
    await client.query('COMMIT');
    const user = userResult.rows[0];
    res.json({ token: sign(user), user });
  } catch (e) {
    await client.query('ROLLBACK');
    console.error('OTP verification failed:', e.message);
    res.status(500).json({ error: 'SERVER_ERROR' });
  } finally { client.release(); }
});
app.get('/me', auth, async (req, res) => {
  const r = await pool.query('SELECT id, phone_e164, display_name, avatar_url, created_at FROM users WHERE id=$1', [req.user.sub]);
  if (!r.rowCount) return res.status(404).json({ error: 'USER_NOT_FOUND' });
  res.json(r.rows[0]);
});
app.patch('/me', auth, async (req, res) => {
  const name = String(req.body?.display_name || '').trim().slice(0, 40);
  if (!name) return res.status(400).json({ error: 'DISPLAY_NAME_REQUIRED' });
  const r = await pool.query('UPDATE users SET display_name=$1, updated_at=NOW() WHERE id=$2 RETURNING id, phone_e164, display_name, avatar_url', [name, req.user.sub]);
  if (!r.rowCount) return res.status(404).json({ error: 'USER_NOT_FOUND' });
  res.json(r.rows[0]);
});
app.get('/users/lookup', auth, async (req, res) => {
  const phone = String(req.query.phone || '');
  if (!isPhoneE164(phone)) return res.status(400).json({ error: 'PHONE_MUST_BE_E164' });
  const r = await pool.query('SELECT id, phone_e164, display_name, avatar_url FROM users WHERE phone_e164=$1 AND id<>$2', [phone, req.user.sub]);
  if (!r.rowCount) return res.status(404).json({ error: 'USER_NOT_FOUND' });
  res.json(r.rows[0]);
});
app.get('/conversations', auth, async (req, res) => {
  const r = await pool.query(\`
    SELECT c.id AS conversation_id, u.id AS user_id, u.phone_e164, u.display_name, u.avatar_url,
      m.body AS last_message, m.created_at AS last_message_at
    FROM conversation_members mine
    JOIN conversations c ON c.id=mine.conversation_id AND c.kind='direct'
    JOIN conversation_members other ON other.conversation_id=c.id AND other.user_id<>mine.user_id
    JOIN users u ON u.id=other.user_id
    LEFT JOIN LATERAL (SELECT body,created_at FROM messages WHERE conversation_id=c.id ORDER BY created_at DESC LIMIT 1) m ON TRUE
    WHERE mine.user_id=$1 ORDER BY m.created_at DESC NULLS LAST LIMIT 100
  \`, [req.user.sub]);
  res.json(r.rows);
});
async function findOrCreateDirectConversation(client, firstId, secondId) {
  const existing = await client.query(\`
    SELECT c.id FROM conversations c
    JOIN conversation_members a ON a.conversation_id=c.id AND a.user_id=$1
    JOIN conversation_members b ON b.conversation_id=c.id AND b.user_id=$2
    WHERE c.kind='direct' AND (SELECT COUNT(*) FROM conversation_members cm WHERE cm.conversation_id=c.id)=2
    LIMIT 1
  \`, [firstId, secondId]);
  if (existing.rowCount) return existing.rows[0].id;
  const c = await client.query("INSERT INTO conversations(kind,created_by) VALUES('direct',$1) RETURNING id", [firstId]);
  await client.query('INSERT INTO conversation_members(conversation_id,user_id) VALUES($1,$2),($1,$3) ON CONFLICT DO NOTHING', [c.rows[0].id, firstId, secondId]);
  return c.rows[0].id;
}
app.post('/messages', auth, async (req, res) => {
  const recipient = Number(req.body?.recipient_id), body = cleanMessage(req.body?.body);
  if (!Number.isSafeInteger(recipient) || recipient < 1 || recipient === Number(req.user.sub) || !isValidMessage(body)) return res.status(400).json({ error: 'INVALID_MESSAGE' });
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const exists = await client.query('SELECT id FROM users WHERE id=$1', [recipient]);
    if (!exists.rowCount) { await client.query('ROLLBACK'); return res.status(404).json({ error: 'RECIPIENT_NOT_FOUND' }); }
    const conversationId = await findOrCreateDirectConversation(client, req.user.sub, recipient);
    const inserted = await client.query('INSERT INTO messages(conversation_id,sender_id,body) VALUES($1,$2,$3) RETURNING id,conversation_id,sender_id,body,created_at', [conversationId, req.user.sub, body]);
    await client.query('COMMIT');
    const message = { ...inserted.rows[0], recipient_id: recipient };
    broadcast(recipient, { type: 'message', message });
    res.status(201).json(message);
  } catch (e) { await client.query('ROLLBACK'); console.error(e); res.status(500).json({ error: 'SERVER_ERROR' }); }
  finally { client.release(); }
});
app.get('/messages/:userId', auth, async (req, res) => {
  const other = Number(req.params.userId);
  if (!Number.isSafeInteger(other) || other < 1 || other === Number(req.user.sub)) return res.status(400).json({ error: 'INVALID_USER' });
  const r = await pool.query(\`
    SELECT m.id,m.conversation_id,m.sender_id,m.body,m.created_at,m.read_at
    FROM messages m
    JOIN conversation_members a ON a.conversation_id=m.conversation_id AND a.user_id=$1
    JOIN conversation_members b ON b.conversation_id=m.conversation_id AND b.user_id=$2
    ORDER BY m.created_at ASC LIMIT 200
  \`, [req.user.sub, other]);
  res.json(r.rows);
});
app.post('/messages/:messageId/read', auth, async (req, res) => {
  const id = Number(req.params.messageId);
  if (!Number.isSafeInteger(id) || id < 1) return res.status(400).json({ error: 'INVALID_MESSAGE' });
  const r = await pool.query(\`
    UPDATE messages m SET read_at=NOW()
    WHERE m.id=$1 AND m.sender_id<>$2
      AND EXISTS(SELECT 1 FROM conversation_members cm WHERE cm.conversation_id=m.conversation_id AND cm.user_id=$2)
    RETURNING m.id,m.read_at
  \`, [id, req.user.sub]);
  if (!r.rowCount) return res.status(404).json({ error: 'MESSAGE_NOT_FOUND' });
  res.json(r.rows[0]);
});
const wss = new WebSocketServer({ noServer: true });
const clients = new Map();
function broadcast(userId, data) {
  for (const ws of clients.get(String(userId)) || []) if (ws.readyState === 1) ws.send(JSON.stringify(data));
}
server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname !== '/ws') return socket.destroy();
  try {
    const token = url.searchParams.get('token') || '';
    const user = jwt.verify(token, jwtSecret || 'local-development-secret-change-me', { issuer: 'goregram-api' });
    wss.handleUpgrade(req, socket, head, ws => {
      const id = String(user.sub);
      if (!clients.has(id)) clients.set(id, new Set());
      clients.get(id).add(ws);
      ws.on('close', () => { clients.get(id)?.delete(ws); if (!clients.get(id)?.size) clients.delete(id); });
      ws.send(JSON.stringify({ type: 'connected' }));
    });
  } catch { socket.destroy(); }
});
const port = Number(process.env.PORT || 8080);
server.listen(port, () => console.log('Goregram API listening on :' + port));
