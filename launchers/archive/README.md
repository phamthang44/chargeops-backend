# Archived launchers

These batch and PowerShell launchers were moved here so the project root has one clear entry point. They are retained unchanged for rollback/reference and may expect to be restored to their original locations before they are run.

- `pre-one-click/` contains the launchers that were previously at the backend project root.
- `retired-domain/` contains partial domain/demo steps now handled by `launchers/demo/start.bat`.
- `previous-tailscale-demo/` contains the old scripts under `scripts/` that used Tailscale.
- `backend-release.with-dokploy.yml` and `retired-domain/dokploy-release.py` preserve the removed server-deploy path. The active GitHub workflow runs quality checks and can optionally publish an image; it has no VPS deploy job.

Use `START-CHARGEOPS.bat` for current local development and demo workflows.
