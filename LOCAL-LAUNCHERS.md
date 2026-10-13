# Local launchers

There is one launcher at the project root: double-click `START-CHARGEOPS.bat`, then choose the mode you need. You can also run the role-specific scripts directly from `launchers/`.

- `launchers/dev/start.bat` starts the local development stack. Docker runs Postgres, Redis, Elasticsearch, Keycloak, Kafka and Mailpit; separate windows run Spring Boot, the Vite console and Expo Web. Open `http://localhost:5173` for the console and `http://localhost:8082` for Driver Web. The launcher points Expo at the local backend and Keycloak.
- `launchers/demo/start.bat` builds and starts the full local demo stack, including the Web Portal container, then runs `npm start -- --web` for Driver on port 8082. The gateway sends `driver.chargeops.online` to that Expo Web server on the host. Hostinger SMTP and the Cloudflare Tunnel are enabled in this mode. It requires `.env`, `.env.cloudflared`, working Hostinger SMTP settings, and the existing Tunnel routes for `chargeops.online` and `driver.chargeops.online`.
- `launchers/dev/stop.bat` and `launchers/demo/stop.bat` stop their Docker services without deleting volumes. Stop the host Spring Boot/Vite/Expo windows with Ctrl+C.

The launchers check the ports they need and show an error if another process is using one. They may stop the other ChargeOps Compose mode when switching between dev and demo, but never run `down -v` or delete database volumes. Stop existing app windows with Ctrl+C before switching modes.

In local dev, Keycloak uses `http://localhost:8080` and password login. Google sign-in uses the public broker callback, so use the domain demo for Google OAuth.

Old, specialized, domain and release launchers are kept under `launchers/archive/` for rollback/reference. Restore a backup to its previous project-root or `scripts/` location before running it because some old files rely on their original relative paths. Frontend-only launchers that duplicated the backend entry points are archived in the frontend repository's `launchers/archive/`.

The current demo intentionally runs Expo Web on the development machine for fast iteration. A future server deployment can replace that process with a static Driver Web container served by Nginx; that is separate from the local launcher and does not require Expo Go.
