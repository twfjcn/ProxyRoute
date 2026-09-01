#!/system/bin/sh

TARGET_DIR="/data/local/proxy"
LOG_FILE="$TARGET_DIR/run.log"
PID_FILE="$TARGET_DIR/proxy.pid"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [STOP] $1" >> $LOG_FILE
}

log "停止代理服务..."

# 停止进程
if [ -f $PID_FILE ]; then
    PID=$(cat $PID_FILE)
    if kill -0 $PID 2>/dev/null; then
        kill $PID
        log "已终止进程 PID: $PID"
    fi
    rm -f $PID_FILE
fi

# 清理路由规则
DEFAULT_IFACE=$(ip route show default 2>/dev/null | grep -oP 'dev \K\S+' | head -1)
if [ -n "$DEFAULT_IFACE" ]; then
    ip rule del fwmark 0x1 table 100 2>/dev/null
    ip route flush table 100 2>/dev/null
    iptables -t nat -D POSTROUTING -o $DEFAULT_IFACE -j MASQUERADE 2>/dev/null
    log "已清理路由规则 (接口: $DEFAULT_IFACE)"
fi

# 创建停止标记
touch /data/local/proxy/stop.flag

log "代理服务已停止"
