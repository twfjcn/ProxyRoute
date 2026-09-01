#!/system/bin/sh
# ============================================
# 代理守护脚本 - 自动检测接口
# ============================================

TARGET_DIR="/data/local/proxy"
LOG_FILE="$TARGET_DIR/run.log"
PID_FILE="$TARGET_DIR/proxy.pid"

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [INFO] $1" >> $LOG_FILE
}

# 获取实际可用的网络接口
get_network_iface() {
    local iface=$(ip route show default 2>/dev/null | grep -oP 'dev \K\S+' | head -1)
    if [ -n "$iface" ]; then
        echo "$iface"
        return
    fi
    iface=$(ip addr show | grep -E '^[0-9]+:' | grep -v lo | grep -v tun | grep -v vpn | head -1 | awk -F': ' '{print $2}')
    if [ -n "$iface" ]; then
        echo "$iface"
        return
    fi
    iface=$(ip link show | grep -E '^[0-9]+:' | grep -v lo | head -1 | awk -F': ' '{print $2}')
    if [ -n "$iface" ]; then
        echo "$iface"
        return
    fi
    echo "wlan0"
}

log "========================================"
log "代理守护启动"

echo 1 > /proc/sys/net/ipv4/ip_forward
log "IP 转发已启用"

IFACE=$(get_network_iface)
log "检测到网络接口: $IFACE"

if ! ip link show "$IFACE" > /dev/null 2>&1; then
    log "错误: 接口 $IFACE 不存在"
    IFACE=$(ip link show | grep -E '^[0-9]+:' | grep -v lo | head -1 | awk -F': ' '{print $2}')
    if [ -z "$IFACE" ]; then
        log "错误: 无法找到任何网络接口"
        exit 1
    fi
    log "使用备用接口: $IFACE"
fi

log "清理旧规则..."
ip rule del fwmark 0x1 table 100 2>/dev/null
ip route flush table 100 2>/dev/null
iptables -t nat -F 2>/dev/null
iptables -F 2>/dev/null

log "设置路由规则..."
ip rule add fwmark 0x1 table 100 priority 100 2>/dev/null
if [ $? -ne 0 ]; then
    log "警告: 添加路由规则失败"
fi

ip route add default dev $IFACE table 100 2>/dev/null
if [ $? -ne 0 ]; then
    log "警告: 添加默认路由失败"
fi

log "设置 iptables 规则..."
iptables -t nat -A POSTROUTING -o $IFACE -j MASQUERADE 2>/dev/null
iptables -A FORWARD -i $IFACE -o $IFACE -j ACCEPT 2>/dev/null
iptables -A FORWARD -i $IFACE -j ACCEPT 2>/dev/null
iptables -A FORWARD -o $IFACE -j ACCEPT 2>/dev/null

log "路由规则设置完成 (接口: $IFACE)"

echo $$ > $PID_FILE
log "代理服务已启动 (PID: $$)"

while true; do
    sleep 30
    if [ -f "/data/local/proxy/stop.flag" ]; then
        log "收到停止信号"
        break
    fi
done

log "代理服务已停止"
rm -f $PID_FILE
