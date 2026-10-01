# Deploy on AWS (one free-tier VM: app + Postgres + HTTPS)

```
Internet -> Caddy (HTTPS) -> Java app -> Postgres      all containers on ONE EC2 instance in Mumbai
```

Why one VM: app-to-database latency is ~0 ms, which is what makes the burst fast. Data lives on the VM's EBS disk
(snapshot it for backup, step 10).

## 0. Avoid surprise charges (do this first)
- Billing console -> **Budgets** -> create a **zero-spend / $1 budget** with an email alert.
- AWS's free tier terms differ by account age (older accounts: 12 months of free hours; newer accounts: a free plan
  with credits). Read the **Free Tier** page in your Billing console to see what applies to you.

## 1. Region
Top-right of the console: choose **Asia Pacific (Mumbai) ap-south-1**.

## 2. Launch the instance (EC2 -> Launch instance)
| Field | Value |
|---|---|
| Name | `seats` |
| AMI | **Ubuntu Server 24.04 LTS** (marked *Free tier eligible*) |
| Instance type | **pick one labelled "Free tier eligible"** in the dropdown. Usually `t3.micro` (1 GB RAM); if `t3.small` (2 GB) is also labelled eligible for your account, prefer it. Do NOT pick anything without the label. |
| Key pair | Create new (RSA, `.pem`); the file downloads once - keep it safe |
| Network / security group | Allow **SSH (22) from "My IP"**, **HTTP (80)** and **HTTPS (443)** from anywhere (0.0.0.0/0) |
| Storage | **30 GiB gp3** (the free-tier maximum) |

Click **Launch instance**.

## 3. Fixed public IP
EC2 -> **Elastic IPs** -> Allocate -> Associate with the instance. (Keep it attached to a *running* instance; an
unattached IP can be billed. Release it when you tear everything down.)

Note the IP, e.g. `13.232.10.20`. Your hostname will be the IP with dashes + `.sslip.io`: `13-232-10-20.sslip.io`
(sslip.io is a free DNS service that resolves such names to the IP, which lets Caddy get a real HTTPS certificate
without you owning a domain).

## 4. Connect
```sh
chmod 400 seats.pem                       # Windows PowerShell can skip this; use the same ssh command
ssh -i seats.pem ubuntu@13.232.10.20
```

## 5. One-time machine setup
```sh
git clone https://github.com/AniketP-hub/bookmyshow.git
cd bookmyshow
./deploy/ec2-setup.sh
exit        # log out, then ssh back in so the docker group takes effect
```

## 6. Configure and start
```sh
cd bookmyshow
cp .env.example .env
nano .env        # set DB_PASSWORD, ADMIN_TOKEN, TOKEN_SECRET (long random), DOMAIN=13-232-10-20.sslip.io
docker compose -f docker-compose.aws.yml up -d --build
```
The first build takes ~5-10 minutes on a micro instance (swap makes it fit in memory).

## 7. Check it is up
```sh
docker compose -f docker-compose.aws.yml ps
curl https://13-232-10-20.sslip.io/readyz        # {"status":"ready","db":"up"}
```
If HTTPS is not ready yet, wait a minute (Caddy is fetching the certificate) and check
`docker compose -f docker-compose.aws.yml logs caddy`.

## 8. Run the burst (from your laptop)
```powershell
$env:ADMIN_TOKEN="<the ADMIN_TOKEN you put in .env>"
java burst\Burst.java https://13-232-10-20.sslip.io
```
Put the URL in README.md. Metrics: `https://<host>/metrics`.

## 9. Logs
```sh
docker compose -f docker-compose.aws.yml logs -f app        # structured JSON logs, with request_id
```
To give reviewers log access, either run a short screen recording of this command during the burst or paste a sample.

## 10. Backup and update
- **Backup:** EC2 -> Volumes -> select the volume -> Actions -> **Create snapshot** (the first 1 GB-ish is cheap/free;
  delete old snapshots).
- **Update code:** `cd bookmyshow && git pull && docker compose -f docker-compose.aws.yml up -d --build`

## 11. Tear down (to stop all charges)
Terminate the instance, release the Elastic IP, delete leftover volumes and snapshots.

## Troubleshooting
- Build killed / "Killed": swap missing - rerun `./deploy/ec2-setup.sh`, or use a larger eligible instance.
- Browser can't reach the site: check the security group allows 80 and 443, and `DOMAIN` matches the Elastic IP.
- Memory: measured under the full burst, app ~500 MB + Postgres ~100 MB + Caddy ~15 MB, so a 1 GB instance works only with the swap
  from the setup script (throughput is lower, ~650 req/s vs ~1,400 with more heap). A 2 GB instance is noticeably better:
  set `JAVA_TOOL_OPTIONS=-Xmx900m -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1` in `.env`.
- 5xx during the burst on a micro instance: check `docker stats`; if memory is exhausted, move to a larger
  free-tier-eligible type or lower `DB_POOL_MAX`.
