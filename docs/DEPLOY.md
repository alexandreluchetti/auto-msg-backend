# Deploy on a VPS

Everything runs on one VPS with Docker Compose (D-008, ARCHITECTURE section 15):
[`deploy/docker-compose.prod.yml`](../deploy/docker-compose.prod.yml).

| Service | What |
|---|---|
| `web` | Caddy built from [`auto-msg-frontend`](https://github.com/alexandreluchetti/auto-msg-frontend): TLS certificate for `DOMAIN` (Let's Encrypt), the panel's static files, and the proxy of `/api`, `/webhooks` and `/actuator/health` to the app |
| `app` | This backend (port 8080 on the internal network only) |
| `postgres` | PostgreSQL 17 + pgvector, data in the `postgres-data` volume |
| `backup` | `pg_dump` at start and every day at `BACKUP_TIME` (UTC), kept `BACKUP_KEEP_DAYS` days in the `backups` volume |
| `offsite` (profile) | rclone copy of the backups to S3-compatible storage every 6 h |
| `prometheus`, `grafana` (profile `ops`) | Metrics and the "Conversa — operação" dashboard, Grafana on `127.0.0.1:3000` |

## 1. Server

- A VPS with 2 vCPU and 4 GB of RAM is enough for the pilot (≤ 50 orders/day), Ubuntu 24.04.
- Install Docker Engine with the Compose plugin; open only ports 22, 80 and 443.
- Point a DNS `A` record (e.g. `painel.<domain>`) to the server. Caddy needs it to issue the
  certificate.

## 2. Configuration

```bash
git clone https://github.com/alexandreluchetti/auto-msg-backend.git
cd auto-msg-backend/deploy
cp ../.env.example .env
chmod 600 .env
```

Fill in `deploy/.env` (the README lists every variable). Besides the app's settings:

| Variable | Value |
|---|---|
| `POSTGRES_PASSWORD` | Long random password (also used by the app and the backup) |
| `DOMAIN` | The panel's host name, e.g. `painel.example.com.br` |
| `ACME_EMAIL` | E-mail for Let's Encrypt notices |
| `BOOTSTRAP_DATA_DIR` | `/data` (put `menu.csv`, `options.csv`, `hours.csv`, `zones.csv` in `deploy/data/`, see [IMPORT.md](IMPORT.md)) |
| `BOOTSTRAP_STAFF_EMAIL` / `BOOTSTRAP_STAFF_PASSWORD` | The restaurant's panel user |
| `GRAFANA_ADMIN_PASSWORD` | Only with `--profile ops` |
| `BACKUP_RCLONE_REMOTE` | Only with `--profile offsite`, e.g. `s3:conversa-backups` (rclone config in `deploy/rclone/rclone.conf`) |

`SESSION_COOKIE_SECURE` is forced to `true` by the compose file.

## 3. Start

```bash
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml logs -f app
curl https://$DOMAIN/actuator/health    # {"status":"UP"}
```

Then, in the Meta app (WhatsApp → Configuration), set the webhook URL to
`https://<DOMAIN>/webhooks/whatsapp` with the `WHATSAPP_VERIFY_TOKEN` of `.env`, and subscribe the
fields `messages` and `smb_message_echoes` (coexistence).

## 4. Updates

```bash
git pull
docker compose -f docker-compose.prod.yml up -d --build
```

Flyway migrates the database on start. The panel image is built from the `main` branch of
`auto-msg-frontend` (`PANEL_BUILD_CONTEXT` overrides it, e.g. a local checkout).

## 5. Backups and restore drill

- Backups land in the `backups` volume as `conversa-<UTC stamp>.dump`; each one is checked with
  `pg_restore --list` before it is kept. With `--profile offsite` they are also copied off the
  server (recommended: storage in another provider).
- Restore drill (do it once after going live, then every few months): restore into a new database
  and check it, leaving the live one untouched:

  ```bash
  docker compose -f docker-compose.prod.yml exec backup sh -c 'ls -lt /backups | head'
  docker compose -f docker-compose.prod.yml exec backup sh /scripts/restore.sh /backups/conversa-<stamp>.dump
  ```

  `BackupRestoreIT` runs the same scripts in the build.
- Real recovery: stop `app`, restore into `conversa_restore`, rename the databases
  (`ALTER DATABASE conversa RENAME TO conversa_old; ALTER DATABASE conversa_restore RENAME TO conversa;`),
  start `app`.

## 6. Monitoring

```bash
docker compose -f docker-compose.prod.yml --profile ops up -d
ssh -L 3000:127.0.0.1:3000 <server>     # then open http://localhost:3000
```

The dashboard shows LLM cost and tokens, billable WhatsApp messages by category, orders and their
value, handoffs by reason, queue backlog and HTTP errors, per tenant. Metrics come from
`/actuator/prometheus`, which only the internal network reaches.
