#!/system/bin/sh
PID=$(pgrep -f "proxy.sh")
if [ -n "$PID" ];then
    kill $PID
fi
pref=18000
ip rule del pref $pref 2>/dev/null
ip rule del pref $(expr $pref - 1) 2>/dev/null
iptables -t nat -D POSTROUTING -o tun0 -j MASQUERADE 2>/dev/null
echo "stopped"
