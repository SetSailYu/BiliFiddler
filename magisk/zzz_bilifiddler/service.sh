#!/system/bin/sh
# zzz_bilifiddler service.sh - boot restore (runs at late_start, network ready)
MODDIR=${0%/*}
FLAG=/data/local/tmp/bilifiddler.enabled
CONF=/data/local/tmp/bilifiddler.conf
RELAY=/data/local/tmp/bfrelay
PORT=18987

# Only restore if user had capture enabled before reboot
[ -f "$FLAG" ] || exit 0

sleep 5

# pick binary by ABI and deploy
ARCH=$(getprop ro.product.cpu.abi)
case "$ARCH" in
  arm64*) cp "$MODDIR/bfrelay64" "$RELAY" ;;
  *)      cp "$MODDIR/bfrelay32" "$RELAY" ;;
esac
chmod 755 "$RELAY"

# if no conf (first boot after fresh install), fall back to flag-free default
[ -f "$CONF" ] || echo "proxy 10.0.0.230 8888" > "$CONF"

# start relay (fully detached)
pkill -f bfrelay 2>/dev/null
sleep 1
nohup setsid "$RELAY" >/data/local/tmp/bfrelay.log 2>&1 </dev/null &

# resolve bili uid and apply redirect rules
sleep 1
UID=$(dumpsys package tv.danmaku.bili 2>/dev/null | grep -oE 'userId=[0-9]+' | head -1 | cut -d= -f2)
[ -z "$UID" ] && UID=10334

while iptables -t nat -D OUTPUT -m owner --uid-owner $UID -p tcp --dport 443 -j REDIRECT --to-ports $PORT 2>/dev/null; do :; done
while iptables -t nat -D OUTPUT -m owner --uid-owner $UID -p tcp --dport 80 -j REDIRECT --to-ports $PORT 2>/dev/null; do :; done
while iptables -D OUTPUT -m owner --uid-owner $UID -p udp --dport 443 -j REJECT 2>/dev/null; do :; done

iptables -t nat -A OUTPUT -m owner --uid-owner $UID -p tcp --dport 443 -j REDIRECT --to-ports $PORT
iptables -t nat -A OUTPUT -m owner --uid-owner $UID -p tcp --dport 80 -j REDIRECT --to-ports $PORT
iptables -A OUTPUT -m owner --uid-owner $UID -p udp --dport 443 -j REJECT
