'use strict';

require('dotenv').config();
const express = require('express');
const http = require('http');
const crypto = require('crypto');
const cors = require('cors');
const helmet = require('helmet');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const { Pool } = require('pg');
const { Server } = require('socket.io');
const rateLimit = require('express-rate-limit');
const { z } = require('zod');

const required = ['DATABASE_URL', 'JWT_SECRET'];
for (const key of required) {
  if (!process.env[key]) {
    console.error(`Missing required environment variable: ${key}`);
    process.exit(1);
  }
}
if (process.env.JWT_SECRET.length < 32) {
  console.error('JWT_SECRET must contain at least 32 characters.');
  process.exit(1);
}

const app = express();
const server = http.createServer(app);
const pool = new Pool({ connectionString: process.env.DATABASE_URL, ssl: process.env.NODE_ENV === 'production' ? { rejectUnauthorized: true } : undefined });
const origins = (process.env.CORS_ORIGINS || '').split(',').map(v => v.trim()).filter(Boolean);
const io = new Server(server, {
  cors: { origin: origins.length ? origins : false, methods: ['GET', 'POST'] }
});

app.disable('x-powered-by');
app.use(helmet());
app.use(cors({ origin: origins.length ? origins : false }));
app.use(express.json({ limit: '32kb' }));
app.use(rateLimit({ windowMs: 15 * 60 * 1000, limit: 300, standardHeaders: 'draft-7', legacyHeaders: false }));

const phoneSchema = z.string().regex(/^\+[1-9]\d{7,14}$/, 'Phone must use international E.164 format.');
const registerSchema = z.object({
  phone: phoneSchema,
  displayName: z.string().trim().min(2).max(80),
  password: z.string().min(10).max(128),
  inviteToken: z.string().min(32).max(256).optional()
});
const loginSchema = z.object({ phone: phoneSchema, password: z.string().min(1).max(128) });
const messageSchema = z.object({ body: z.string().trim().min(1).max(10000) });

function tokenFor(user) {
  return jwt.sign({ sub: String(user.id) }, process.env.JWT_SECRET, { expiresIn: '7d', issuer: 'goregram-server', audience: 'goregram-app' });
}
function authFromToken(token) {
  return jwt.verify(token, process.env.JWT_SECRET, { issuer: 'goregram-server', audience: 'goregram-app' });
}
async function requireAuth(req, res, next) {
  try {
    const header = req.headers.authorization || '';
    const token = header.startsWith('Bearer ') ? header.slice(7) : '';
    if (!token) return res.status(401).json({ error: 'Authentification requise.' });
    const payload = authFromToken(token);
    const result = await pool.query('SELECT id, phone_e164, display_name FROM users WHERE id = $1', [payload.sub]);
    if (!result.rowCount) return res.status(401).json({ error: 'Session invalide.' });
    req.user = result.rows[0];
    next();
  } catch {
    return res.status(401).json({ error: 'Jeton invalide ou expiré.' });
  }
}

app.get('/health', async (_req, res) => {
  try {
    await pool.query('SELECT 1');
    res.json({ ok: true, service: 'goregram-server', database: 'connected' });
  } catch {
    res.status(503).json({ ok: false, service: 'goregram-server', database: 'unavailable' });
  }
});

app.post('/api/auth/register', rateLimit({ windowMs: 60 * 60 * 1000, limit: 10 }), async (req, res, next) => {
  try {
    const input = registerSchema.parse(req.body);
    let invitation = null;
    if (input.inviteToken) {
      const tokenHash = crypto.createHash('sha256').update(input.inviteToken).digest('hex');
      const found = await pool.query(
        'SELECT id, inviter_user_id FROM invitations WHERE token_hash = $1 AND invitee_phone_e164 = $2 AND accepted_at IS NULL AND expires_at > NOW()',
        [tokenHash, input.phone]
      );
      if (!found.rowCount) return res.status(400).json({ error: "Lien d'invitation invalide, expiré ou associé à un autre numéro." });
      invitation = found.rows[0];
    }
    const rounds = Math.min(14, Math.max(10, Number(process.env.BCRYPT_ROUNDS) || 12));
    const passwordHash = await bcrypt.hash(input.password, rounds);
    const client = await pool.connect();
    let user;
    try {
      await client.query('BEGIN');
      const result = await client.query(
        'INSERT INTO users (phone_e164, display_name, password_hash) VALUES ($1, $2, $3) RETURNING id, phone_e164, display_name',
        [input.phone, input.displayName, passwordHash]
      );
      user = result.rows[0];
      if (invitation) {
        const used = await client.query(
          'UPDATE invitations SET accepted_at = NOW() WHERE id = $1 AND accepted_at IS NULL AND expires_at > NOW() RETURNING id',
          [invitation.id]
        );
        if (!used.rowCount) throw Object.assign(new Error('Invitation already used'), { code: 'INVITE_USED' });
      }
      await client.query('COMMIT');
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally { client.release(); }
    res.status(201).json({ user, token: tokenFor(user), invitedBy: invitation ? invitation.inviter_user_id : null });
  } catch (error) {
    if (error instanceof z.ZodError) return res.status(400).json({ error: error.issues[0].message });
    if (error.code === '23505') return res.status(409).json({ error: 'Ce numéro possède déjà un compte Goregram.' });
    if (error.code === 'INVITE_USED') return res.status(409).json({ error: 'Ce lien d’invitation a déjà été utilisé.' });
    next(error);
  }
});

app.post('/api/auth/login', rateLimit({ windowMs: 15 * 60 * 1000, limit: 10 }), async (req, res, next) => {
  try {
    const input = loginSchema.parse(req.body);
    const result = await pool.query('SELECT id, phone_e164, display_name, password_hash FROM users WHERE phone_e164 = $1', [input.phone]);
    const user = result.rows[0];
    if (!user || !(await bcrypt.compare(input.password, user.password_hash))) {
      return res.status(401).json({ error: 'Numéro ou mot de passe incorrect.' });
    }
    res.json({ user: { id: user.id, phone_e164: user.phone_e164, display_name: user.display_name }, token: tokenFor(user) });
  } catch (error) {
    if (error instanceof z.ZodError) return res.status(400).json({ error: error.issues[0].message });
    next(error);
  }
});

app.get('/api/me', requireAuth, (req, res) => res.json({ user: req.user }));

const invitePhoneSchema = z.object({ phone: phoneSchema });
const inviteLimiter = rateLimit({ windowMs: 60 * 60 * 1000, limit: 5, standardHeaders: 'draft-7', legacyHeaders: false });

app.post('/api/invitations', requireAuth, inviteLimiter, async (req, res, next) => {
  try {
    const input = invitePhoneSchema.parse(req.body);
    const existing = await pool.query('SELECT id FROM users WHERE phone_e164 = $1', [input.phone]);
    if (existing.rowCount) return res.status(409).json({ error: 'Ce numéro possède déjà un compte Goregram.' });
    const rawToken = crypto.randomBytes(32).toString('base64url');
    const tokenHash = crypto.createHash('sha256').update(rawToken).digest('hex');
    await pool.query(
      `INSERT INTO invitations (inviter_user_id, invitee_phone_e164, token_hash, expires_at) VALUES ($1, $2, $3, NOW() + INTERVAL '7 days')`,
      [req.user.id, input.phone, tokenHash]
    );
    const appUrl = (process.env.PUBLIC_APP_URL || 'goregram://invite').replace(/\/$/, '');
    const inviteLink = appUrl.includes('://') ? (appUrl.startsWith('goregram://') ? `${appUrl}?token=${encodeURIComponent(rawToken)}` : `${appUrl}/invite?token=${encodeURIComponent(rawToken)}`) : null;
    if (!inviteLink) return res.status(503).json({ error: 'PUBLIC_APP_URL doit être configurée pour générer le lien de l’application.' });
    const smsBody = `${req.user.display_name} vous invite à rejoindre Goregram. Créez votre compte ici : ${inviteLink} (lien valable 7 jours).`;
    const hasTwilio = process.env.TWILIO_ACCOUNT_SID && process.env.TWILIO_AUTH_TOKEN && process.env.TWILIO_FROM;
    if (hasTwilio) {
      const auth = Buffer.from(`${process.env.TWILIO_ACCOUNT_SID}:${process.env.TWILIO_AUTH_TOKEN}`).toString('base64');
      const form = new URLSearchParams({ To: input.phone, From: process.env.TWILIO_FROM, Body: smsBody });
      const sent = await fetch(`https://api.twilio.com/2010-04-01/Accounts/${process.env.TWILIO_ACCOUNT_SID}/Messages.json`, {
        method: 'POST', headers: { Authorization: `Basic ${auth}`, 'Content-Type': 'application/x-www-form-urlencoded' }, body: form
      });
      if (!sent.ok) {
        const detail = await sent.text();
        console.error('SMS provider rejected invitation:', sent.status, detail.slice(0, 500));
        return res.status(502).json({ error: "L'invitation a été créée, mais l'envoi du SMS a échoué. Vérifiez la configuration SMS." });
      }
      return res.status(201).json({ delivered: true, channel: 'sms', expiresInDays: 7 });
    }
    res.status(201).json({ delivered: false, channel: 'share_link', inviteLink, message: "Lien prêt à partager. Configurez le fournisseur SMS pour l'envoi automatique." });
  } catch (error) {
    if (error instanceof z.ZodError) return res.status(400).json({ error: error.issues[0].message });
    next(error);
  }
});

app.get('/api/invitations/validate/:token', async (req, res, next) => {
  try {
    const tokenHash = crypto.createHash('sha256').update(String(req.params.token)).digest('hex');
    const result = await pool.query(
      `SELECT i.invitee_phone_e164, i.expires_at, u.display_name AS inviter_name
       FROM invitations i JOIN users u ON u.id = i.inviter_user_id
       WHERE i.token_hash = $1 AND i.accepted_at IS NULL AND i.expires_at > NOW()`,
      [tokenHash]
    );
    if (!result.rowCount) return res.status(404).json({ valid: false, error: 'Lien invalide ou expiré.' });
    res.json({ valid: true, phone: result.rows[0].invitee_phone_e164, inviterName: result.rows[0].inviter_name, expiresAt: result.rows[0].expires_at });
  } catch (error) { next(error); }
});


app.post('/api/contacts/lookup', requireAuth, async (req, res, next) => {
  try {
    const input = z.object({ phone: phoneSchema }).parse(req.body);
    const result = await pool.query(
      'SELECT id, display_name FROM users WHERE phone_e164 = $1 AND id <> $2',
      [input.phone, req.user.id]
    );
    // This searches Goregram accounts only; it does not query Telegram or disclose whether a number has a Telegram account.
    res.json({ contact: result.rows[0] || null, network: 'goregram' });
  } catch (error) {
    if (error instanceof z.ZodError) return res.status(400).json({ error: error.issues[0].message });
    next(error);
  }
});

app.post('/api/conversations/direct', requireAuth, async (req, res, next) => {
  const client = await pool.connect();
  try {
    const input = z.object({ userId: z.coerce.number().int().positive() }).parse(req.body);
    if (input.userId === Number(req.user.id)) return res.status(400).json({ error: 'Impossible de créer une conversation avec soi-même.' });
    await client.query('BEGIN');
    const target = await client.query('SELECT id FROM users WHERE id = $1', [input.userId]);
    if (!target.rowCount) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Ce numéro ne possède pas encore de compte Goregram.' });
    }
    const existing = await client.query(
      `SELECT c.id FROM conversations c
       JOIN conversation_members a ON a.conversation_id = c.id AND a.user_id = $1
       JOIN conversation_members b ON b.conversation_id = c.id AND b.user_id = $2
       WHERE c.kind = 'direct'
       AND (SELECT COUNT(*) FROM conversation_members m WHERE m.conversation_id = c.id) = 2
       LIMIT 1`,
      [req.user.id, input.userId]
    );
    let conversationId;
    if (existing.rowCount) conversationId = existing.rows[0].id;
    else {
      const created = await client.query(
        "INSERT INTO conversations (kind, created_by) VALUES ('direct', $1) RETURNING id",
        [req.user.id]
      );
      conversationId = created.rows[0].id;
      await client.query('INSERT INTO conversation_members (conversation_id, user_id) VALUES ($1, $2), ($1, $3)', [conversationId, req.user.id, input.userId]);
    }
    await client.query('COMMIT');
    res.status(200).json({ conversationId });
  } catch (error) {
    await client.query('ROLLBACK');
    if (error instanceof z.ZodError) return res.status(400).json({ error: error.issues[0].message });
    next(error);
  } finally { client.release(); }
});

app.get('/api/conversations/:id/messages', requireAuth, async (req, res, next) => {
  try {
    const id = z.coerce.number().int().positive().parse(req.params.id);
    const member = await pool.query('SELECT 1 FROM conversation_members WHERE conversation_id = $1 AND user_id = $2', [id, req.user.id]);
    if (!member.rowCount) return res.status(403).json({ error: 'Accès refusé à cette conversation.' });
    const result = await pool.query(
      'SELECT id, conversation_id, sender_id, body, created_at FROM messages WHERE conversation_id = $1 ORDER BY created_at DESC LIMIT 50',
      [id]
    );
    res.json({ messages: result.rows.reverse() });
  } catch (error) { next(error); }
});

app.post('/api/conversations/:id/messages', requireAuth, async (req, res, next) => {
  try {
    const id = z.coerce.number().int().positive().parse(req.params.id);
    const input = messageSchema.parse(req.body);
    const member = await pool.query('SELECT 1 FROM conversation_members WHERE conversation_id = $1 AND user_id = $2', [id, req.user.id]);
    if (!member.rowCount) return res.status(403).json({ error: 'Accès refusé à cette conversation.' });
    const result = await pool.query(
      'INSERT INTO messages (conversation_id, sender_id, body) VALUES ($1, $2, $3) RETURNING id, conversation_id, sender_id, body, created_at',
      [id, req.user.id, input.body]
    );
    const message = result.rows[0];
    io.to(`conversation:${id}`).emit('message:new', message);
    res.status(201).json({ message });
  } catch (error) {
    if (error instanceof z.ZodError) return res.status(400).json({ error: error.issues[0].message });
    next(error);
  }
});

io.use((socket, next) => {
  try {
    const token = socket.handshake.auth && socket.handshake.auth.token;
    if (!token) return next(new Error('Authentification requise.'));
    socket.user = authFromToken(token);
    next();
  } catch { next(new Error('Jeton invalide ou expiré.')); }
});
io.on('connection', socket => {
  socket.on('conversation:join', async conversationId => {
    const id = Number(conversationId);
    if (!Number.isSafeInteger(id) || id < 1) return;
    const member = await pool.query('SELECT 1 FROM conversation_members WHERE conversation_id = $1 AND user_id = $2', [id, socket.user.sub]);
    if (member.rowCount) socket.join(`conversation:${id}`);
  });
});

app.use((err, _req, res, _next) => {
  console.error(err);
  if (err instanceof z.ZodError) return res.status(400).json({ error: err.issues[0].message });
  res.status(500).json({ error: 'Erreur interne du serveur.' });
});

const port = Number(process.env.PORT) || 4000;
server.listen(port, () => console.log(`Goregram API listening on port ${port}`));
process.on('SIGTERM', async () => { server.close(); await pool.end(); process.exit(0); });
