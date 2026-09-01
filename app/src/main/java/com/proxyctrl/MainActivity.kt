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

    private fun extractAsset(assetName: String, destPath: String): Boolean {
        return try {
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
        // 检查进程
        val out = runSu("ps -A 2>/dev/null | grep -E 'proxy.sh' | grep -v grep")
        return out.isNotEmpty() && !out.startsWith("ERR")
    }

    private fun getPid(): String {
        val pidFile = runSu("cat $targetDir/proxy.pid 2>/dev/null")
        if (pidFile.isNotEmpty() && pidFile.matches(Regex("\\d+"))) {
            return pidFile
        }
        val out = runSu("pgrep -f proxy.sh 2>/dev/null")
        if (out.isNotEmpty() && !out.startsWith("ERR") && out.matches(Regex("\\d+"))) {
            return out
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
                    running -> "状态：VPN 转发正在运行 (PID: ${getPid()})"
                    else -> "状态：VPN 转发已停止"
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
                runSu("rm -f $targetDir/proxy.pid")

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
                val checkScript = runSu("test -f $scriptProxy && echo EXISTS")
                if (!checkScript.contains("EXISTS")) {
                    launch(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "脚本不存在，请先初始化", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                if (isRunning()) {
                    launch(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "VPN 转发已在运行", Toast.LENGTH_SHORT).show()
                    }
                    refreshUi()
                    return@launch
                }

                runSu("rm -f $targetDir/stop.flag")
                runSu("nohup sh $scriptProxy >> $logFile 2>&1 &")

                launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "已发送启动请求", Toast.LENGTH_SHORT).show()
                    delay(1500)
                    refreshUi()
                }
            }
        }

        btnStop.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val checkScript = runSu("test -f $scriptStop && echo EXISTS")
                if (!checkScript.contains("EXISTS")) {
                    launch(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "停止脚本不存在，请先初始化", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                runSu("sh $scriptStop")
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

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }
}
