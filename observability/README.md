# ChargeOps observability

The local stack contains:

- Prometheus scraping Spring Boot metrics from `http://host.docker.internal:8081/actuator/prometheus`.
- Loki storing application logs.
- Grafana Alloy tailing `logs/chargeops-app.log` and sending it to Loki.
- Grafana with provisioned Prometheus/Loki data sources and the **ChargeOps Overview** dashboard.

## Run

1. Start the ChargeOps backend locally on port `8081`.
2. Start the infrastructure:

   ```shell
   docker compose up -d
   ```

3. Configure the `chargeops-observability` confidential client in Keycloak and
   assign the `OBSERVABILITY_ADMIN` realm role to the allowed account.
4. Open the admin portal and choose **Observability**. During local development
   Vite proxies `/grafana/` to the loopback-only Grafana port.

   Grafana has no local password login. Prometheus, Loki, and Alloy are only
   reachable on the Docker network; Grafana is their sole browser-facing UI.

Grafana loads the **ChargeOps Overview** dashboard automatically. New log lines are collected after Alloy starts; historical archived files are intentionally excluded to prevent duplicate ingestion.

## Production notes

- Do not publish Loki, Prometheus, Alloy, or actuator endpoints directly to the internet.
- Keep Grafana behind the web reverse proxy and Keycloak OAuth.
- Replace local filesystem storage with durable object storage and configure backups before production use.
