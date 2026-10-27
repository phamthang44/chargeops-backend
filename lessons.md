# Engineering lessons

- 2026-10-27: NGINX maps with long exact keys (the OIDC token route) need `map_hash_bucket_size 128`. Verify the rendered template with real `nginx -t`; a text/config parser alone missed this failure. Integration fixtures must show stderr and isolate PID, logs and every temporary path.

- 2026-10-27: Run WSL test setup from a script file; nested PowerShell / wsl / bash quoting expanded shell variables in the wrong layer and downloaded two fixture packages into the workspace. Removed those artifacts and kept setup in an explicit .sh with LF endings.
