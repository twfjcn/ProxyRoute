package com.proxyctrl

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnExtract: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnViewLog: Button

    private val targetDir = "/data/local/proxy"
    private val scriptProxy = "$targetDir/proxy.sh"
    private val scriptStop = "$targetDir/stop.sh"
    private val logFile = "$targetDir/run.log"

    private fun runSu(cmd: String): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val br = BufferedReader(InputStreamReader(proc.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (br.readLine().also { line = it } != null) {
                sb.appendLine(line)
            }
            val errBr = BufferedReader(InputStreamReader(proc.errorStream))
            var errLine: String?
            while (errBr.readLine().also { errLine = it } != null) {
                sb.appendLine("ERR: $errLine")
            }
            proc.waitFor()
            sb.toString().trim()
        } catch (e: Exception) {
            "ERR:${e.message}"
        }
    }

    private fun assetExists(assetName: String): Boolean {
        return try {
            assets.open(assetName).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun generateProxyScript(): String {
        return """#!/system/bin/sh
# ============================================
# 代理守护脚本 - 自动生成
# ============================================

TARGET_DIR="/data/local/proxy"
LOG_FILE="$${TARGET_DIR}/run.log"
PID_FILE="$${TARGET_DIR}/proxy.pid"

log() {
    echo "[\$(date '+%Y-%m-%d %H:%M:%S')] \$1" >> $${LOG_FILE}
}

# 获取默认路由接口
get_default_iface() {
    local iface=\$(ip route show default 2>/dev/null | grep -oP 'dev \K\S+' | head -1)
    if [ -z "\$iface" ]; then
        iface=\$(ip link show | grep -E '^[0-9]+:' | grep -v lo | head -1 | awk -F': ' '{print \$2}')
    fi
    if [ -z "\$iface" ]; then
        iface="wlan0"
    fi
    echo "\$iface"
}

log "========================================"
log "代理守护启动"

# 启用 IP 转发
echo 1 > /proc/sys/net/ipv4/ip_forward
log "IP 转发已启用"

# 获取接口
IFACE=\$(get_default_iface)
log "使用接口: \$IFACE"

# 清理旧规则
iptables -t nat -F 2>/dev/null
iptables -F 2>/dev/null
ip rule del fwmark 0x1 table 100 2>/dev/null
ip route flush table 100 2>/dev/null

# 添加转发规则
iptables -t nat -A POSTROUTING -o \$IFACE -j MASQUERADE 2>/dev/null
iptables -A FORWARD -i \$IFACE -o \$IFACE -j ACCEPT 2>/dev/null
iptables -A FORWARD -i \$IFACE -j ACCEPT 2>/dev/null
iptables -A FORWARD -o \$IFACE -j ACCEPT 2>/dev/null

# 添加路由规则
ip rule add fwmark 0x1 table 100 priority 100 2>/dev/null
ip route add default dev \$IFACE table 100 2>/dev/null

log "路由规则设置完成"

# 记录 PID
echo \$\$ > $${PID_FILE}
log "代理服务已启动 (PID: \$\$)"

# 保持运行
while true; do
    sleep 30
    if [ -f "/data/local/proxy/stop.flag" ]; then
        log "收到停止信号"
        break
    fi
done

log "代理服务已停止"
rm -f $${PID_FILE}
"""
    }

    private fun generateStopScript(): String {
        return """#!/system/bin/sh
# ============================================
# 停止脚本 - 自动生成
# ============================================

TARGET_DIR="/data/local/proxy"
LOG_FILE="$${TARGET_DIR}/run.log"
PID_FILE="$${TARGET_DIR}/proxy.pid"

log() {
    echo "[\$(date '+%Y-%m-%d %H:%M:%S')] [STOP] \$1" >> $${LOG_FILE}
}

log "========================================"
log "开始停止代理服务..."

# 停止进程
if [ -f $${PID_FILE} ]; then
    PID=\$(cat $${PID_FILE})
    if kill -0 \$PID 2>/dev/null; then
        kill -9 \$PID
        log "已强制终止进程 PID: \$PID"
    fi
    rm -f $${PID_FILE}
fi

# 获取接口
IFACE=\$(ip route show default 2>/dev/null | grep -oP 'dev \K\S+' | head -1)
if [ -z "\$IFACE" ]; then
    IFACE="wlan0"
fi

# 清理规则
ip rule del fwmark 0x1 table 100 2>/dev/null
ip route flush table 100 2>/dev/null
iptables -t nat -F 2>/dev/null
iptables -F 2>/dev/null
iptables -t nat -D POSTROUTING -o \$IFACE -j MASQUERADE 2>/dev/null

# 创建停止标记
touch /data/local/proxy/stop.flag

# 清理其他相关进程
pkill -f proxy.sh 2>/dev/null
pkill -f "sh.*proxy.sh" 2>/dev/null

log "代理服务已停止"
rm -f $${PID_FILE}
"""
    }

    private fun extractAsset(assetName: String, destPath: String): Boolean {
        return try {
            if (assetExists(assetName)) {
                val input = assets.open(assetName)
                val bytes = input.readBytes()
                input.close()
                val tempFile = cacheDir.resolve(assetName)
                tempFile.writeBytes(bytes)
                
                val mkdirResult = runSu("mkdir -p $targetDir")
                if (mkdirResult.startsWith("ERR")) {
                    tempFile.delete()
                    return false
                }
                
                val cpResult = runSu("cp ${tempFile.absolutePath} $destPath")
                if (cpResult.startsWith("ERR")) {
                    tempFile.delete()
                    return false
                }
                
                val chmodResult = runSu("chmod 755 $destPath")
                tempFile.delete()
                
                !chmodResult.startsWith("ERR")
            } else {
                val content = if (assetName == "proxy.sh") generateProxyScript() else generateStopScript()
                val tempFile = cacheDir.resolve(assetName)
                tempFile.writeText(content)
                
                val mkdirResult = runSu("mkdir -p $targetDir")
                if (mkdirResult.startsWith("ERR")) {
                    tempFile.delete()
                    return false
                }
                
                val cpResult = runSu("cp ${tempFile.absolutePath} $destPath")
                if (cpResult.startsWith("ERR")) {
                    tempFile.delete()
                    return false
                }
                
                val chmodResult = runSu("chmod 755 $destPath")
                tempFile.delete()
                
                !chmodResult.startsWith("ERR")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun isScriptInstalled(): Boolean {
        val ret = runSu("ls -l $scriptProxy 2>/dev/null && echo EXISTS")
        return ret.contains("EXISTS") && !ret.startsWith("ERR")
    }

    private fun isRunning(): Boolean {
        val out = runSu("ps -A | grep -E 'proxy.sh|bash.*proxy.sh|sh.*proxy.sh' | grep -v grep")
        return out.isNotEmpty() && !out.startsWith("ERR")
    }

    private fun refreshUi() {
        CoroutineScope(Dispatchers.IO).launch {
            val installed = isScriptInstalled()
            val running = if (installed) isRunning() else false
            launch(Dispatchers.Main) {
                btnExtract.isEnabled = !installed
                btnStart.isEnabled = installed && !running
                btnStop.isEnabled = installed && running
                btnViewLog.isEnabled = installed
                tvStatus.text = when {
                    !installed -> "状态：脚本未释放，请点初始化"
                    running -> "状态：守护正在运行"
                    else -> "状态：守护已停止"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tv_status)
        btnExtract = findViewById(R.id.btn_extract)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnViewLog = findViewById(R.id.btn_view_log)

        btnExtract.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                runSu("rm -f $scriptProxy $scriptStop")
                runSu("rm -f $targetDir/stop.flag")
                
                val hasProxy = assetExists("proxy.sh")
                val hasStop = assetExists("stop.sh")
                
                launch(Dispatchers.Main) {
                    if (!hasProxy) {
                        Toast.makeText(this@MainActivity, "未找到 proxy.sh，将使用内置脚本", Toast.LENGTH_LONG).show()
                    }
                    if (!hasStop) {
                        Toast.makeText(this@MainActivity, "未找到 stop.sh，将使用内置脚本", Toast.LENGTH_LONG).show()
                    }
                }
                
                val ok1 = extractAsset("proxy.sh", scriptProxy)
                val ok2 = extractAsset("stop.sh", scriptStop)
                
                launch(Dispatchers.Main) {
                    if (ok1 && ok2) {
                        Toast.makeText(this@MainActivity, "脚本释放成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "释放失败，请确认授予ROOT权限", Toast.LENGTH_SHORT).show()
                    }
                    refreshUi()
                }
            }
        }

        btnStart.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                runSu("rm -f $targetDir/stop.flag")
                runSu("nohup sh $scriptProxy > $logFile 2>&1 &")
                launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "已发送启动请求", Toast.LENGTH_SHORT).show()
                    refreshUi()
                }
            }
        }

        btnStop.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                runSu("sh $scriptStop")
                runSu("pkill -f proxy.sh 2>/dev/null")
                launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "已发送停止请求", Toast.LENGTH_SHORT).show()
                    refreshUi()
                }
            }
        }

        btnViewLog.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val logText = runSu("tail -n 40 $logFile 2>/dev/null")
                launch(Dispatchers.Main) {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("运行日志")
                        .setMessage(if (logText.isBlank() || logText.startsWith("ERR")) "日志为空" else logText)
                        .setPositiveButton("关闭", null)
                        .show()
                }
            }
        }
        refreshUi()
    }
}
