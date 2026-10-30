# Deploying the service: Render (service) + Neon (database)

Both free tiers, both in Frankfurt, the closest Render region to Lagos. The service is the Docker image built from
`Dockerfile`; nothing in it depends on Render, so moving host only changes the deploy step.

| Piece | Provider and plan | Region |
| --- | --- | --- |
| Service | Render, Free web service (Docker) | Frankfurt (EU Central) |
| Database | Neon, Free plan, PostgreSQL | AWS Europe Central 1 (Frankfurt) |
| Interface | Ufoma's static host (Netlify, Vercel or Cloudflare Pages) | CDN |

## 1. Database (Neon)

1. Project `bloodlink`, region **AWS Europe Central 1 (Frankfurt)**.
2. **Connect**, with **Connection pooling off**: use the **direct** host (no `-pooler` in it). The service sets the time zone
   on each connection, which a transaction pooler would lose.
3. Note the PostgreSQL version (Settings). CI tests use `postgres:17-alpine` (`AbstractPostgresIT`); if Neon runs a
   different major version, change that one line to match.
4. Turn Neon's connection string into the three settings the service reads:

   | Neon gives | Render setting |
   | --- | --- |
   | `postgresql://USER:PASSWORD@HOST/DBNAME?sslmode=require&channel_binding=require` | |
   | `HOST` and `DBNAME` | `DATABASE_URL` = `jdbc:postgresql://HOST/DBNAME?sslmode=require` |
   | `USER` | `DATABASE_USERNAME` |
   | `PASSWORD` | `DATABASE_PASSWORD` |

   The JDBC form starts with `jdbc:` and has no user or password in it. Never commit any of these values.

## 2. Service (Render)

1. **New > Web Service**, connect GitHub, choose `Fisayomii/Bloodlink`.
2. Settings:

   | Field | Value |
   | --- | --- |
   | Name | `bloodlink-api` (becomes `https://bloodlink-api.onrender.com`, or similar) |
   | Language / Runtime | **Docker** |
   | Branch | `main` |
   | Region | **Frankfurt (EU Central)** |
   | Instance type | **Free** |
   | Dockerfile path | `./Dockerfile` |
   | Health check path | `/actuator/health` |

3. **Environment variables**:

   | Key | Value |
   | --- | --- |
   | `DATABASE_URL` | `jdbc:postgresql://<neon host>/<db>?sslmode=require` |
   | `DATABASE_USERNAME` | from Neon |
   | `DATABASE_PASSWORD` | from Neon |
   | `JWT_SECRET` | a new random value, see below |
   | `ALLOWED_ORIGINS` | `http://localhost:5173` for now; add the interface's address when it is live, comma-separated |
   | `LIQUIBASE_CONTEXTS` | `demo` |
   | `LOGGING_STRUCTURED_FORMAT_CONSOLE` | `ecs` (one JSON object per log line, request id included) |
   | `DB_POOL_SIZE` | `5` |

   Generate `JWT_SECRET` in PowerShell (48 random bytes, base64):

   ```powershell
   $b = New-Object byte[] 48; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
   ```

4. **Create Web Service.** The first build takes several minutes (Maven downloads, then the startup-cache practice run).
   When it is live, `https://<service>/actuator/health` shows `{"status":"UP"}` and `https://<service>/` shows Swagger.
   On first start the service creates the tables and loads the demonstration data in Neon.
5. **Settings > Build & Deploy > Auto-Deploy: Off.** Deploys must come from GitHub Actions after the checks pass.
6. **Settings > Deploy Hook**: copy the URL. Treat it as a secret.

## 3. Connect the pipeline (GitHub)

Repository **Settings > Secrets and variables > Actions**:

| Where | Name | Value |
| --- | --- | --- |
| Secrets | `API_DEPLOY_HOOK` | Render's deploy hook URL |
| Secrets | `PROD_DATABASE_URL`, `PROD_DATABASE_USERNAME`, `PROD_DATABASE_PASSWORD` | Neon's direct connection, for migrations (see "Migrations from the pipeline") |
| Variables | `API_URL` | `https://<service>.onrender.com` (no trailing slash) |
| Variables | `KEEP_WARM` | `false` (set `true` on demo days, see below) |

From now on, every merge to `main` runs Contract lint and Build and test; only if both pass does **Deploy** call the hook
and wait until `API_URL/actuator/health` reports UP. Re-run the latest `main` run (Actions > api > Re-run all jobs) to
check the hook works.

## 4. Check it

- [ ] `https://<service>/actuator/health` shows `{"status":"UP"}`
- [ ] Swagger at `https://<service>/`: **POST /sessions** as `staff@lagos-central.bank.demo` / `Demo-Password-1` gives 201
- [ ] **GET /requests?state=PENDING** as that account shows the three hospitals' requests for the last O-negative unit
- [ ] The Actions run for `main` shows **Deploy** green, without the "skipping deploy" notice
- [x] README: live addresses added
- [ ] Neon: reset the database password (Roles), update `DATABASE_PASSWORD` on Render, redeploy. The first password was
      shared in a chat.

## Keeping it awake on demo days

Render's free service sleeps after 15 minutes without traffic, and waking takes a while. On demo and assessment days set
the repository variable `KEEP_WARM` to `true`: `.github/workflows/keep-warm.yml` then pings `/actuator/health` every
10 minutes from 07:00 to 21:00 Lagos time. Set it back to `false` afterwards: Render's 750 free hours a month are shared by
every service in the workspace (BloodLink and FairSeat). GitHub can delay scheduled runs, so also open the health URL
yourself 10 minutes before presenting.

## Free-tier terms (checked 2026-10-25 on the providers' own pages)

| Provider | Terms that matter |
| --- | --- |
| Render Free web service | 512 MB; sleeps after 15 minutes without inbound traffic; 750 free instance hours per workspace per month |
| Neon Free | Permanent plan, no card; 1 GB storage per project; 100 compute-hours per project per month; compute suspends after 5 minutes idle and resumes on the next connection; limits suspend compute, never delete data |
| Render Free Postgres (not used) | Expires 30 days after creation: the reason the database is on Neon |
| Supabase Free (not used) | Projects pause after one week without activity; direct connection is IPv6-only, which Render cannot reach |

## Required checks on `main`

Repository **Settings > Rules > Rulesets > Protect main > Require status checks to pass**: add **Contract tests** and
**Migrations** next to **Contract lint** and **Build and test**, so a pull request whose build diverges from the contract,
or whose migration cannot be rolled back, cannot be merged.

## Migrations from the pipeline

The **Deploy** job migrates the live database before it starts the new version (`mvn liquibase:update`, the same
Liquibase version and files as the service). Set up once:

1. In GitHub, **Settings > Secrets and variables > Actions**, add three repository secrets from Neon's **direct**
   connection (not the pooler): `PROD_DATABASE_URL` in JDBC form (`jdbc:postgresql://<host>/<db>?sslmode=require`),
   `PROD_DATABASE_USERNAME` and `PROD_DATABASE_PASSWORD`. Use the rotated password; never paste it anywhere else.
2. Push any change to `main` and check that **Deploy > Migrate the live database** says the database is up to date.
3. In Render, set `LIQUIBASE_ENABLED=false`, so the service no longer migrates itself.

Until the secrets exist the step only warns, and the service keeps migrating itself at start.

**Rolling back the live database** (after rolling the code back first): from your machine, with the same three values,

```powershell
mvn -B liquibase:rollback "-Dliquibase.rollbackCount=1" "-Dliquibase.url=$env:PROD_DATABASE_URL" `
  "-Dliquibase.username=$env:PROD_DATABASE_USERNAME" "-Dliquibase.password=$env:PROD_DATABASE_PASSWORD" "-Dliquibase.contexts=demo"
```

Every changeset has a rollback, and the **Migrations** job proves on every pull request that all of them run, down to the
empty baseline and back up again. A migration must work with the version still running, because the old version keeps
serving until the new one is healthy.

