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
    echo "[$(date '+%H:%M:%S')] [STOP] $1" >> $LOG_FILE
}

log "========================================"
log "开始停止 VPN 代理转发服务"

# 创建停止标记
touch /data/local/proxy/stop.flag

# 停止守护进程
if [ -f $PID_FILE ]; then
    PID=$(cat $PID_FILE)
    if kill -0 $PID 2>/dev/null; then
        kill -9 $PID
        log "已终止守护进程 PID: $PID"
    fi
    rm -f $PID_FILE
fi

# 清理所有相关进程
pkill -f proxy.sh 2>/dev/null
pkill -f "sh.*proxy.sh" 2>/dev/null
log "已清理所有相关进程"

# 清理路由策略
ip rule del from all table main pref $pref 2>/dev/null
ip rule del from all iif $dev table $tun pref $(expr $pref - 1) 2>/dev/null
ip route flush table $tun 2>/dev/null
log "路由策略已清理"

# 清理 iptables 规则
iptables -t nat -D POSTROUTING -o $tun -j MASQUERADE 2>/dev/null
iptables -F FORWARD 2>/dev/null
log "iptables 规则已清理"

# 清理停止标记
rm -f /data/local/proxy/stop.flag

log "VPN 代理转发服务已完全停止"
