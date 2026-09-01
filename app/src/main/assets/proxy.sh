#!/system/bin/sh
# ============================================
# VPN 代理转发守护脚本
# ============================================

TARGET_DIR="/data/local/proxy"
LOG_FILE="$TARGET_DIR/run.log"
PID_FILE="$TARGET_DIR/proxy.pid"

# 配置参数（可根据需要修改）
tun='tun0'          # 虚拟接口名称
dev='wlan0'         # 物理接口名称，eth0、wlan0
interval=3          # 检测网络状态间隔(秒)
pref=18000          # 路由策略优先级

log() {
    echo "[$(date '+%H:%M:%S')] $1" >> $LOG_FILE
}

log "========================================"
log "VPN 代理转发守护启动"

# 开启IP转发功能
sysctl -w net.ipv4.ip_forward=1 2>/dev/null
log "IP 转发已开启"

# 清除filter表转发链规则
iptables -F FORWARD 2>/dev/null
log "iptables FORWARD 链已清除"

# 添加NAT转换
iptables -t nat -A POSTROUTING -o $tun -j MASQUERADE 2>/dev/null
log "NAT 转换已添加 (出口: $tun)"

# 清理可能存在的旧路由策略
ip rule del from all table main pref $pref 2>/dev/null
ip rule del from all iif $dev table $tun pref $(expr $pref - 1) 2>/dev/null

# 添加路由策略
ip rule add from all table main pref $pref 2>/dev/null
ip rule add from all iif $dev table $tun pref $(expr $pref - 1) 2>/dev/null
log "路由策略已添加 (接口: $dev -> 表: $tun)"

contain="from all iif $dev lookup $tun"

# 记录 PID
echo $$ > $PID_FILE
log "守护进程已启动 (PID: $$)"

# 主循环 - 监控网络变化
while true ; do
    if [[ $(ip rule 2>/dev/null) != *$contain* ]]; then
        if [[ $(ip ad 2>/dev/null | grep 'state UP') != *$dev* ]]; then
            log "物理接口 $dev 已断开"
        else
            ip rule add from all iif $dev table $tun pref $(expr $pref - 1) 2>/dev/null
            log "网络变化，已重置路由策略"
        fi
    fi
    
    # 检查停止标志
    if [ -f "/data/local/proxy/stop.flag" ]; then
        log "收到停止信号"
        break
    fi
    
    sleep $interval
done

log "VPN 代理转发守护已停止"
rm -f $PID_FILE
