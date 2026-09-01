#!/system/bin/sh

# ============================================
# 代理守护脚本 - 修复版
# ============================================

TARGET_DIR="/data/local/proxy"
LOG_FILE="$TARGET_DIR/run.log"
PID_FILE="$TARGET_DIR/proxy.pid"

# 日志函数
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> $LOG_FILE
}

# 检查 root 权限
check_root() {
    if [ "$(id -u)" != "0" ]; then
        log "错误: 需要 root 权限"
        exit 1
    fi
}

# 获取默认路由接口
get_default_iface() {
    # 尝试获取默认路由的接口
    local iface=$(ip route show default 2>/dev/null | grep -oP 'dev \K\S+' | head -1)
    if [ -z "$iface" ]; then
        # 备选方案：获取第一个非lo接口
        iface=$(ip link show | grep -E '^[0-9]+:' | grep -v lo | head -1 | awk -F': ' '{print $2}')
    fi
    echo "$iface"
}

# 设置路由策略
setup_routing() {
    local iface=$(get_default_iface)
    
    if [ -z "$iface" ]; then
        log "错误: 无法获取网络接口"
        return 1
    fi
    
    log "检测到网络接口: $iface"
    
    # 启用 IP 转发
    echo 1 > /proc/sys/net/ipv4/ip_forward
    log "IP 转发已启用"
    
    # 清空可能存在的旧规则（使用实际接口名）
    ip rule del fwmark 0x1 table 100 2>/dev/null
    ip route flush table 100 2>/dev/null
    
    # 添加新的路由规则（使用实际的接口）
    ip rule add fwmark 0x1 table 100 priority 100 2>/dev/null
    if [ $? -eq 0 ]; then
        log "路由规则添加成功 (使用接口: $iface)"
    else
        log "路由规则添加失败，尝试备选方案"
        # 备选方案：直接使用路由表
        ip route add default dev $iface table 100 2>/dev/null
    fi
    
    # 添加 NAT 规则（如果需要）
    iptables -t nat -D POSTROUTING -o $iface -j MASQUERADE 2>/dev/null
    iptables -t nat -A POSTROUTING -o $iface -j MASQUERADE 2>/dev/null
    
    log "路由设置完成"
}

# 启动代理服务
start_proxy() {
    log "启动代理服务..."
    
    # 清除旧的 PID 文件
    rm -f $PID_FILE
    
    # 设置路由
    setup_routing
    
    # 在这里启动你的代理程序
    # 例如：
    # /data/local/proxy/your_proxy_binary &
    # 或者使用 iptables 重定向
    
    # 记录 PID
    echo $$ > $PID_FILE
    
    log "代理服务已启动 (PID: $$)"
    
    # 保持脚本运行
    while true; do
        sleep 10
        # 检查网络变化
        if [ -f "/data/local/proxy/stop.flag" ]; then
            log "收到停止信号"
            break
        fi
    done
}

# 主函数
main() {
    check_root
    
    log "========================================"
    log "代理守护启动"
    
    # 检查是否已运行
    if [ -f $PID_FILE ]; then
        local old_pid=$(cat $PID_FILE 2>/dev/null)
        if kill -0 $old_pid 2>/dev/null; then
            log "代理已在运行 (PID: $old_pid)"
            exit 1
        fi
        rm -f $PID_FILE
    fi
    
    start_proxy
}

# 执行主函数
main
