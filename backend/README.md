# Goregram independent messaging server

This service is a separate messaging network for Goregram. It does not replace Telegram's servers or grant access to Telegram accounts.

## Requirements

- Node.js 20+
- PostgreSQL 14+
- A private deployment with HTTPS
- A long random `JWT_SECRET` (at least 32 characters)

## Local setup

1. Copy `.env.example` to `.env` and set `DATABASE_URL`, `JWT_SECRET`, and the allowed `CORS_ORIGINS`.
2. Create a PostgreSQL database and apply the schema:

   `psql "$DATABASE_URL" -f db/schema.sql`

3. Install and start:

   `npm install`
   
   `npm start`

4. Check `GET /health`.

## Initial API

- `POST /api/auth/register` — create a Goregram account using an E.164 phone number, display name and password.
- `POST /api/auth/login` — sign in.
- `GET /api/me` — current account.
- `POST /api/contacts/lookup` — find an existing Goregram account by exact phone number.
- `POST /api/conversations/direct` — create or reuse a direct conversation.
- `GET /api/conversations/:id/messages` — read recent messages.
- `POST /api/conversations/:id/messages` — send a message.
- Socket.IO: authenticate with `auth.token`, then emit `conversation:join`; new messages arrive as `message:new`.

All database values are passed as SQL parameters. Passwords are hashed; API routes are authenticated and rate limited.

## Important network distinction

A phone number is an address for a Goregram account only after its owner registers on Goregram. This server cannot discover private Telegram accounts, send unsolicited messages through Telegram, or remove Telegram's server-side contact/privacy restrictions. Telegram remains a separate network. A later, explicit interoperability feature must use Telegram's supported APIs and permissions; it cannot promise delivery to arbitrary numbers.

## Not production-ready yet

Before public launch, add phone ownership verification (OTP), account recovery, stronger abuse/spam controls, message pagination, push notifications, media object storage, database migrations, automated tests, monitoring, backups, and end-to-end encryption. Do not expose this server publicly until these are implemented and reviewed.
