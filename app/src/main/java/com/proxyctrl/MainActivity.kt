package com.proxyctrl

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        return StringBuilder()
            .append("#!/system/bin/sh\n")
            .append("# ============================================\n")
            .append("# 代理守护脚本 - 自动检测接口\n")
            .append("# ============================================\n")
            .append("\n")
            .append("TARGET_DIR=\"/data/local/proxy\"\n")
            .append("LOG_FILE=\"$TARGET_DIR/run.log\"\n")
            .append("PID_FILE=\"$TARGET_DIR/proxy.pid\"\n")
            .append("\n")
            .append("log() {\n")
            .append("    echo \"$(date '+%Y-%m-%d %H:%M:%S') [INFO] $1\" >> $LOG_FILE\n")
            .append("}\n")
            .append("\n")
            .append("# 获取实际可用的网络接口\n")
            .append("get_network_iface() {\n")
            .append("    local iface=$(ip route show default 2>/dev/null | grep -oP 'dev \\K\\S+' | head -1)\n")
            .append("    if [ -n \"$iface\" ]; then\n")
            .append("        echo \"$iface\"\n")
            .append("        return\n")
            .append("    fi\n")
            .append("    iface=$(ip addr show | grep -E '^[0-9]+:' | grep -v lo | grep -v tun | grep -v vpn | head -1 | awk -F': ' '{print $2}')\n")
            .append("    if [ -n \"$iface\" ]; then\n")
            .append("        echo \"$iface\"\n")
            .append("        return\n")
            .append("    fi\n")
            .append("    iface=$(ip link show | grep -E '^[0-9]+:' | grep -v lo | head -1 | awk -F': ' '{print $2}')\n")
            .append("    if [ -n \"$iface\" ]; then\n")
            .append("        echo \"$iface\"\n")
            .append("        return\n")
            .append("    fi\n")
            .append("    echo \"wlan0\"\n")
            .append("}\n")
            .append("\n")
            .append("log \"========================================\"\n")
            .append("log \"代理守护启动\"\n")
            .append("\n")
            .append("echo 1 > /proc/sys/net/ipv4/ip_forward\n")
            .append("log \"IP 转发已启用\"\n")
            .append("\n")
            .append("IFACE=$(get_network_iface)\n")
            .append("log \"检测到网络接口: $IFACE\"\n")
            .append("\n")
            .append("if ! ip link show \"$IFACE\" > /dev/null 2>&1; then\n")
            .append("    log \"错误: 接口 $IFACE 不存在\"\n")
            .append("    IFACE=$(ip link show | grep -E '^[0-9]+:' | grep -v lo | head -1 | awk -F': ' '{print $2}')\n")
            .append("    if [ -z \"$IFACE\" ]; then\n")
            .append("        log \"错误: 无法找到任何网络接口\"\n")
            .append("        exit 1\n")
            .append("    fi\n")
            .append("    log \"使用备用接口: $IFACE\"\n")
            .append("fi\n")
            .append("\n")
            .append("log \"清理旧规则...\"\n")
            .append("ip rule del fwmark 0x1 table 100 2>/dev/null\n")
            .append("ip route flush table 100 2>/dev/null\n")
            .append("iptables -t nat -F 2>/dev/null\n")
            .append("iptables -F 2>/dev/null\n")
            .append("\n")
            .append("log \"设置路由规则...\"\n")
            .append("ip rule add fwmark 0x1 table 100 priority 100 2>/dev/null\n")
            .append("if [ $? -ne 0 ]; then\n")
            .append("    log \"警告: 添加路由规则失败\"\n")
            .append("fi\n")
            .append("\n")
            .append("ip route add default dev $IFACE table 100 2>/dev/null\n")
            .append("if [ $? -ne 0 ]; then\n")
            .append("    log \"警告: 添加默认路由失败\"\n")
            .append("fi\n")
            .append("\n")
            .append("log \"设置 iptables 规则...\"\n")
            .append("iptables -t nat -A POSTROUTING -o $IFACE -j MASQUERADE 2>/dev/null\n")
            .append("iptables -A FORWARD -i $IFACE -o $IFACE -j ACCEPT 2>/dev/null\n")
            .append("iptables -A FORWARD -i $IFACE -j ACCEPT 2>/dev/null\n")
            .append("iptables -A FORWARD -o $IFACE -j ACCEPT 2>/dev/null\n")
            .append("\n")
            .append("log \"路由规则设置完成 (接口: $IFACE)\"\n")
            .append("\n")
            .append("echo $$ > $PID_FILE\n")
            .append("log \"代理服务已启动 (PID: $$)\"\n")
            .append("\n")
            .append("while true; do\n")
            .append("    sleep 30\n")
            .append("    if [ -f \"/data/local/proxy/stop.flag\" ]; then\n")
            .append("        log \"收到停止信号\"\n")
            .append("        break\n")
            .append("    fi\n")
            .append("done\n")
            .append("\n")
            .append("log \"代理服务已停止\"\n")
            .append("rm -f $PID_FILE\n")
            .toString()
    }

    private fun generateStopScript(): String {
        return StringBuilder()
            .append("#!/system/bin/sh\n")
            .append("# ============================================\n")
            .append("# 停止脚本\n")
            .append("# ============================================\n")
            .append("\n")
            .append("TARGET_DIR=\"/data/local/proxy\"\n")
            .append("LOG_FILE=\"$TARGET_DIR/run.log\"\n")
            .append("PID_FILE=\"$TARGET_DIR/proxy.pid\"\n")
            .append("\n")
            .append("log() {\n")
            .append("    echo \"$(date '+%Y-%m-%d %H:%M:%S') [STOP] $1\" >> $LOG_FILE\n")
            .append("}\n")
            .append("\n")
            .append("# 获取实际接口\n")
            .append("get_network_iface() {\n")
            .append("    local iface=$(ip route show default 2>/dev/null | grep -oP 'dev \\K\\S+' | head -1)\n")
            .append("    if [ -n \"$iface\" ]; then\n")
            .append("        echo \"$iface\"\n")
            .append("        return\n")
            .append("    fi\n")
            .append("    iface=$(ip link show | grep -E '^[0-9]+:' | grep -v lo | head -1 | awk -F': ' '{print $2}')\n")
            .append("    if [ -n \"$iface\" ]; then\n")
            .append("        echo \"$iface\"\n")
            .append("        return\n")
            .append("    fi\n")
            .append("    echo \"wlan0\"\n")
            .append("}\n")
            .append("\n")
            .append("log \"========================================\"\n")
            .append("log \"开始停止代理服务...\"\n")
            .append("\n")
            .append("# 停止进程\n")
            .append("if [ -f $PID_FILE ]; then\n")
            .append("    PID=$(cat $PID_FILE)\n")
            .append("    if kill -0 $PID 2>/dev/null; then\n")
            .append("        kill -9 $PID\n")
            .append("        log \"已强制终止进程 PID: $PID\"\n")
            .append("    fi\n")
            .append("    rm -f $PID_FILE\n")
            .append("fi\n")
            .append("\n")
            .append("# 获取接口并清理规则\n")
            .append("IFACE=$(get_network_iface)\n")
            .append("log \"清理接口 $IFACE 的规则...\"\n")
            .append("\n")
            .append("# 清理路由规则\n")
            .append("ip rule del fwmark 0x1 table 100 2>/dev/null\n")
            .append("ip route flush table 100 2>/dev/null\n")
            .append("\n")
            .append("# 清理 iptables 规则\n")
            .append("iptables -t nat -F 2>/dev/null\n")
            .append("iptables -F 2>/dev/null\n")
            .append("iptables -t nat -D POSTROUTING -o $IFACE -j MASQUERADE 2>/dev/null\n")
            .append("\n")
            .append("# 创建停止标记\n")
            .append("touch /data/local/proxy/stop.flag\n")
            .append("\n")
            .append("# 清理进程\n")
            .append("pkill -f proxy.sh 2>/dev/null\n")
            .append("pkill -f \"sh.*proxy.sh\" 2>/dev/null\n")
            .append("\n")
            .append("log \"代理服务已停止\"\n")
            .append("rm -f $PID_FILE\n")
            .toString()
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
        val ret = runSu("test -f $scriptProxy && echo EXISTS")
        return ret.contains("EXISTS") && !ret.startsWith("ERR")
    }

    private fun isRunning(): Boolean {
        // 尝试多种方式检测进程
        val out1 = runSu("ps -A 2>/dev/null | grep -E 'proxy.sh' | grep -v grep")
        if (out1.isNotEmpty() && !out1.startsWith("ERR")) {
            return true
        }
        val out2 = runSu("pgrep -f proxy.sh 2>/dev/null")
        if (out2.isNotEmpty() && !out2.startsWith("ERR") && out2.matches(Regex("\\d+"))) {
            return true
        }
        // 检查 PID 文件
        val pidCheck = runSu("test -f $targetDir/proxy.pid && echo EXISTS")
        if (pidCheck.contains("EXISTS")) {
            val pid = runSu("cat $targetDir/proxy.pid 2>/dev/null")
            if (pid.isNotEmpty() && pid.matches(Regex("\\d+"))) {
                val processCheck = runSu("kill -0 $pid 2>/dev/null && echo ALIVE")
                if (processCheck.contains("ALIVE")) {
                    return true
                }
            }
        }
        return false
    }

    private fun getPid(): String {
        val out = runSu("pgrep -f proxy.sh 2>/dev/null")
        if (out.isNotEmpty() && !out.startsWith("ERR") && out.matches(Regex("\\d+"))) {
            return out
        }
        val pidFile = runSu("cat $targetDir/proxy.pid 2>/dev/null")
        if (pidFile.isNotEmpty() && pidFile.matches(Regex("\\d+"))) {
            return pidFile
        }
        return "未知"
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
                    running -> "状态：守护正在运行 (PID: ${getPid()})"
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
                // 先检查脚本是否存在
                val checkScript = runSu("test -f $scriptProxy && echo EXISTS")
                if (!checkScript.contains("EXISTS")) {
                    launch(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "脚本不存在，请先初始化", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // 检查是否已经在运行
                if (isRunning()) {
                    launch(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "代理已在运行", Toast.LENGTH_SHORT).show()
                    }
                    refreshUi()
                    return@launch
                }

                // 清除停止标记
                runSu("rm -f $targetDir/stop.flag")

                // 启动脚本
                runSu("nohup sh $scriptProxy >> $logFile 2>&1 &")

                launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "已发送启动请求", Toast.LENGTH_SHORT).show()
                    // 延迟一下再刷新UI，让进程有时间启动
                    delay(1000)
                    refreshUi()
                }
            }
        }

        btnStop.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                // 先检查脚本是否存在
                val checkScript = runSu("test -f $scriptStop && echo EXISTS")
                if (!checkScript.contains("EXISTS")) {
                    launch(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "停止脚本不存在，请先初始化", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // 执行停止脚本
                runSu("sh $scriptStop")

                // 强制清理
                runSu("pkill -f proxy.sh 2>/dev/null")
                runSu("rm -f $targetDir/proxy.pid")

                launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "已发送停止请求", Toast.LENGTH_SHORT).show()
                    delay(500)
                    refreshUi()
                }
            }
        }

        btnViewLog.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val logText = runSu("tail -n 50 $logFile 2>/dev/null")
                launch(Dispatchers.Main) {
                    val message = if (logText.isBlank() || logText.startsWith("ERR")) {
                        "日志为空或文件不存在"
                    } else {
                        logText
                    }
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("运行日志")
                        .setMessage(message)
                        .setPositiveButton("关闭", null)
                        .setNeutralButton("清空日志") { _, _ ->
                            CoroutineScope(Dispatchers.IO).launch {
                                runSu("echo '' > $logFile")
                            }
                        }
                        .show()
                }
            }
        }

        // 初始刷新
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }
}
