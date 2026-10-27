#!/usr/bin/env bash
# Test-only binaries unpacked into /tmp; never installs packages or uses shared Redis.
set -euo pipefail
if ss -ltnH | awk '{print $4}' | grep -q ':16379$'; then
  echo 'Refusing to reuse an occupied test port 16379' >&2; exit 1
fi
task_dir=$(mktemp -d /tmp/chargeops-abuse.XXXXXX)
cd "$task_dir"
apt download redis-server redis-tools libjemalloc2 liblzf1 liblua5.1-0 libatomic1 nginx libpcre2-8-0
for package in ./*.deb; do dpkg-deb -x "$package" .; done
export LD_LIBRARY_PATH="$task_dir/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
./usr/bin/redis-server --bind 127.0.0.1 --protected-mode yes --port 16379 \
  --save '' --appendonly no --daemonize yes --pidfile "$task_dir/redis.pid" --logfile "$task_dir/redis.log"
./usr/bin/redis-cli -p 16379 ping
echo "TEST_RUNTIME=$task_dir"
