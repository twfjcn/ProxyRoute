#!/system/bin/sh
# ============================================
# 停止脚本
# ============================================

TARGET_DIR="/data/local/proxy"
LOG_FILE="$TARGET_DIR/run.log"
PID_FILE="$TARGET_DIR/proxy.pid"

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [STOP] $1" >> $LOG_FILE
}

# 获取实际接口
get_network_iface() {
    local iface=$(ip route show default 2>/dev/null | grep -oP 'dev \K\S+' | head -1)
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
log "开始停止代理服务..."

# 停止进程
if [ -f $PID_FILE ]; then
    PID=$(cat $PID_FILE)
    if kill -0 $PID 2>/dev/null; then
        kill -9 $PID
        log "已强制终止进程 PID: $PID"
    fi
    rm -f $PID_FILE
fi

# 获取接口并清理规则
IFACE=$(get_network_iface)
log "清理接口 $IFACE 的规则..."

# 清理路由规则
ip rule del fwmark 0x1 table 100 2>/dev/null
ip route flush table 100 2>/dev/null

# 清理 iptables 规则
iptables -t nat -F 2>/dev/null
iptables -F 2>/dev/null
iptables -t nat -D POSTROUTING -o $IFACE -j MASQUERADE 2>/dev/null

# 创建停止标记
touch /data/local/proxy/stop.flag

# 清理进程
pkill -f proxy.sh 2>/dev/null
pkill -f "sh.*proxy.sh" 2>/dev/null

log "代理服务已停止"
rm -f $PID_FILE
