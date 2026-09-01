#!/system/bin/sh
# ============================================
# VPN 代理转发脚本 - 支持 TUN 接口
# ============================================

TARGET_DIR="/data/local/proxy"
LOG_FILE="$TARGET_DIR/run.log"
PID_FILE="$TARGET_DIR/proxy.pid"

# 配置参数
tun='tun0'          # 虚拟接口名称
dev='wlan0'         # 物理接口名称 (eth0, wlan0, rmnet0)
interval=3          # 检测网络状态间隔(秒)
pref=18000          # 路由策略优先级

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [INFO] $1" >> $LOG_FILE
}

# 获取实际物理接口
get_physical_iface() {
    local iface=$(ip route show default 2>/dev/null | grep -oP 'dev \K\S+' | head -1)
    if [ -n "$iface" ] && [ "$iface" != "$tun" ]; then
        echo "$iface"
        return
    fi
    # 尝试获取非虚拟接口
    iface=$(ip link show | grep -E '^[0-9]+:' | grep -v lo | grep -v tun | grep -v vpn | head -1 | awk -F': ' '{print $2}')
    if [ -n "$iface" ]; then
        echo "$iface"
        return
    fi
    echo "wlan0"
}

# 检查 TUN 接口是否存在
check_tun() {
    ip link show "$tun" > /dev/null 2>&1
    return $?
}

# 创建 TUN 接口（如果不存在）
create_tun() {
    if ! check_tun; then
        log "TUN 接口 $tun 不存在，尝试创建..."
        # 使用 ip tuntap 创建 TUN 接口
        ip tuntap add dev $tun mode tun 2>/dev/null
        if [ $? -ne 0 ]; then
            log "警告: ip tuntap 创建失败，尝试使用 openvpn 方式"
            # 有些系统需要用 openvpn --mktun
            openvpn --mktun --dev $tun 2>/dev/null
        fi
        # 启用接口
        ip link set $tun up 2>/dev/null
        # 分配 IP（如果需要）
        ip addr add 10.0.0.1/24 dev $tun 2>/dev/null
        log "TUN 接口创建完成"
    else
        log "TUN 接口 $tun 已存在"
    fi
}

# 设置路由转发
setup_routing() {
    log "========================================"
    log "VPN 代理转发启动"
    
    # 检测物理接口
    dev=$(get_physical_iface)
    log "检测到物理接口: $dev"
    
    # 确保 TUN 接口存在
    create_tun
    
    # 开启 IP 转发
    echo 1 > /proc/sys/net/ipv4/ip_forward
    log "IP 转发已启用"
    
    # 清除 filter 表转发链规则
    iptables -F FORWARD 2>/dev/null
    
    # 清除 NAT 表 POSTROUTING 链中相关规则
    iptables -t nat -D POSTROUTING -o $tun -j MASQUERADE 2>/dev/null
    iptables -t nat -D POSTROUTING -o $dev -j MASQUERADE 2>/dev/null
    
    # 添加 NAT 转换
    iptables -t nat -A POSTROUTING -o $tun -j MASQUERADE 2>/dev/null
    if [ $? -eq 0 ]; then
        log "NAT 规则添加成功 (出口: $tun)"
    else
        # 如果 TUN 不可用，使用物理接口
        iptables -t nat -A POSTROUTING -o $dev -j MASQUERADE 2>/dev/null
        log "NAT 规则添加成功 (出口: $dev)"
    fi
    
    # 添加允许转发规则
    iptables -A FORWARD -i $tun -o $dev -j ACCEPT 2>/dev/null
    iptables -A FORWARD -i $dev -o $tun -j ACCEPT 2>/dev/null
    iptables -A FORWARD -i $tun -j ACCEPT 2>/dev/null
    iptables -A FORWARD -o $tun -j ACCEPT 2>/dev/null
    
    # 清理旧路由策略
    ip rule del from all table main pref $pref 2>/dev/null
    ip rule del from all iif $dev table $tun pref $(expr $pref - 1) 2>/dev/null
    ip route flush table $tun 2>/dev/null
    
    # 添加路由策略
    ip rule add from all table main pref $pref 2>/dev/null
    ip rule add from all iif $dev table $tun pref $(expr $pref - 1) 2>/dev/null
    
    # 添加路由到 TUN 表
    ip route add default dev $tun table $tun 2>/dev/null
    
    log "路由规则设置完成"
    log "物理接口: $dev, 虚拟接口: $tun"
}

# 检查并恢复路由策略
check_and_fix_routing() {
    local contain="from all iif $dev lookup $tun"
    local current_rules=$(ip rule 2>/dev/null)
    
    if [[ "$current_rules" != *"$contain"* ]]; then
        if [[ $(ip link show 2>/dev/null | grep 'state UP') != *"$dev"* ]]; then
            log "警告: 物理接口 $dev 已断开"
        else
            # 检查 TUN 是否存在
            if ! check_tun; then
                log "TUN 接口丢失，重新创建..."
                create_tun
            fi
            # 重新添加路由策略
            ip rule add from all iif $dev table $tun pref $(expr $pref - 1) 2>/dev/null
            ip route add default dev $tun table $tun 2>/dev/null
            log "路由策略已恢复"
        fi
    fi
}

# 主循环
main_loop() {
    log "代理服务已启动 (PID: $$)"
    
    # 设置信号处理
    trap 'log "收到停止信号"; rm -f $PID_FILE; exit 0' TERM INT
    
    while true; do
        check_and_fix_routing
        sleep $interval
    done
}

# 启动
setup_routing
echo $$ > $PID_FILE
main_loop
