#!/system/bin/sh
# ============================================
# VPN 代理转发停止脚本
# ============================================

TARGET_DIR="/data/local/proxy"
LOG_FILE="$TARGET_DIR/run.log"
PID_FILE="$TARGET_DIR/proxy.pid"

# 配置参数
tun='tun0'
dev='wlan0'
pref=18000

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [STOP] $1" >> $LOG_FILE
}

# 获取实际物理接口
get_physical_iface() {
    local iface=$(ip route show default 2>/dev/null | grep -oP 'dev \K\S+' | head -1)
    if [ -n "$iface" ] && [ "$iface" != "$tun" ]; then
        echo "$iface"
        return
    fi
    echo "wlan0"
}

log "========================================"
log "开始停止代理转发服务..."

# 停止进程
if [ -f $PID_FILE ]; then
    PID=$(cat $PID_FILE)
    if kill -0 $PID 2>/dev/null; then
        kill -9 $PID
        log "已强制终止进程 PID: $PID"
    fi
    rm -f $PID_FILE
fi

# 获取物理接口
dev=$(get_physical_iface)
log "物理接口: $dev"

# 清理路由策略
log "清理路由策略..."
ip rule del from all table main pref $pref 2>/dev/null
ip rule del from all iif $dev table $tun pref $(expr $pref - 1) 2>/dev/null
ip route flush table $tun 2>/dev/null

# 清理 iptables 规则
log "清理 iptables 规则..."
iptables -t nat -D POSTROUTING -o $tun -j MASQUERADE 2>/dev/null
iptables -t nat -D POSTROUTING -o $dev -j MASQUERADE 2>/dev/null
iptables -t nat -F 2>/dev/null
iptables -F FORWARD 2>/dev/null
iptables -D FORWARD -i $tun -o $dev -j ACCEPT 2>/dev/null
iptables -D FORWARD -i $dev -o $tun -j ACCEPT 2>/dev/null
iptables -D FORWARD -i $tun -j ACCEPT 2>/dev/null
iptables -D FORWARD -o $tun -j ACCEPT 2>/dev/null

# 创建停止标记
touch /data/local/proxy/stop.flag

# 清理 TUN 接口（可选）
if ip link show $tun > /dev/null 2>&1; then
    ip link set $tun down 2>/dev/null
    ip tuntap del dev $tun mode tun 2>/dev/null
    log "TUN 接口 $tun 已删除"
fi

# 清理进程
pkill -f proxy.sh 2>/dev/null
pkill -f "sh.*proxy.sh" 2>/dev/null

log "代理转发服务已停止"
rm -f $PID_FILE
