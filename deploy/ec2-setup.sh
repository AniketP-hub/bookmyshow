#!/usr/bin/env bash
# One-time setup for an Ubuntu 24.04 EC2 VM: swap (small instances OOM while building Java), Docker, git.
set -euo pipefail

if ! swapon --show | grep -q /swapfile; then
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

sudo apt-get update -y
sudo apt-get install -y docker.io docker-compose-v2 git
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
echo "Done. Log out and back in (so the docker group applies), then follow DEPLOY-AWS.md step 6."

# Let the kernel queue a stampede of simultaneous connections (defaults drop SYNs under an on-sale burst).
sudo tee /etc/sysctl.d/99-seats.conf >/dev/null <<'SYSCTL'
net.core.somaxconn = 8192
net.ipv4.tcp_max_syn_backlog = 8192
net.ipv4.ip_local_port_range = 10240 65535
SYSCTL
sudo sysctl --system >/dev/null
