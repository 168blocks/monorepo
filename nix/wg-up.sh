# wg-up [command [args...]]: brings up the WireGuard interface wg0 of this container's network namespace
# from /etc/wireguard/wg0.conf (wg-quick format, rendered by `gradlew :deploy:wireguard` from
# deploy/wireguard/peers.toml), then runs the command (or, without one, just keeps the namespace alive).
#
# Uses the kernel's WireGuard when it can create the interface, otherwise userspace wireguard-go on
# /dev/net/tun (same configuration). Needs CAP_NET_ADMIN, and /dev/net/tun for the fallback.
# BLOCKS_WG_IMPL=kernel|userspace forces one.
#
# wg-up --wait ADDRESS command...: waits until ADDRESS is assigned in this namespace (the namespace
# owner's wg0 is up), then runs the command; for services that bind to the tunnel address.
set -euo pipefail

has_addr() { [[ $(</proc/net/fib_trie) == *"-- $1"$'\n'* ]]; }

if [ "${1:-}" = --wait ]; then
  addr=$2
  shift 2
  for _ in $(seq 120); do has_addr "$addr" && exec "$@"; sleep 1; done
  echo "wg-up: $addr was not assigned within 120s" >&2
  exit 1
fi

conf=${BLOCKS_WG_CONF:-/etc/wireguard/wg0.conf}
impl=${BLOCKS_WG_IMPL:-auto}
iface=wg0

# wg-quick's [Interface] keys that `wg setconf` does not know are applied here instead
addresses=()
mtu=1420
setconf=$(mktemp)
section=
while IFS= read -r line || [ -n "$line" ]; do
  stripped=${line%%#*}
  key=${stripped%%=*}
  key=${key//[[:space:]]/}
  value=${stripped#*=}
  value=${value//[[:space:]]/}
  case "${key,,}" in
    '[interface]') section=interface ;;
    '[peer]') section=peer ;;
  esac
  if [ "$section" = interface ]; then
    case "${key,,}" in
      address) IFS=, read -ra a <<<"$value"; addresses+=("${a[@]}"); continue ;;
      mtu) mtu=$value; continue ;;
      dns | table | preup | postup | predown | postdown | saveconfig) continue ;;
    esac
  fi
  printf '%s\n' "$line" >>"$setconf"
done <"$conf"

if [ "$impl" != userspace ] && ip link add "$iface" type wireguard 2>/dev/null; then
  echo "wg-up: $iface (kernel WireGuard)"
elif [ "$impl" != kernel ]; then
  mkdir -p /var/run/wireguard
  # in the background for the container's lifetime (also after exec below)
  wireguard-go --foreground "$iface" &
  for _ in $(seq 100); do [ -S "/var/run/wireguard/$iface.sock" ] && break; sleep 0.1; done
  echo "wg-up: $iface (userspace wireguard-go)"
else
  echo "wg-up: cannot create $iface with the kernel's WireGuard" >&2
  exit 1
fi

wg setconf "$iface" "$setconf"
rm -f "$setconf"
for a in "${addresses[@]}"; do ip address add "$a" dev "$iface"; done
ip link set dev "$iface" mtu "$mtu" up
# like wg-quick: a route for every peer's allowed IPs
for cidr in $(wg show "$iface" allowed-ips | cut -f2- | tr ' ' '\n' | sort -u); do
  [ "$cidr" = "(none)" ] || ip route replace "$cidr" dev "$iface"
done
wg show "$iface" # keys hidden

if [ $# -gt 0 ]; then exec "$@"; fi
trap 'exit 0' TERM INT
while :; do
  sleep 3600 &
  wait $! || true
done
