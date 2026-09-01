#!/system/bin/sh
tun='tun0'
dev='wlan0'
interval=3
pref=18000

exec >> /data/local/proxy/run.log 2>&1

sysctl -w net.ipv4.ip_forward=1
iptables -F FORWARD
iptables -t nat -A POSTROUTING -o $tun -j MASQUERADE
ip rule add from all table main pref $pref
ip rule add from all iif $dev table $tun pref $(expr $pref - 1)
contain="from all iif $dev lookup $tun"

while true ;do
    if [[ $(ip rule) != *"$contain"* ]]; then
        if [[ $(ip ad|grep 'state UP') != *"$dev"* ]]; then
            echo "[$(date "+%H:%M:%S")] dev has been lost."
        else
            ip rule add from all iif $dev table $tun pref $(expr $pref - 1)
            echo "[$(date "+%H:%M:%S")] network changed, reset the routing policy."
        fi
    fi
    sleep $interval
done
