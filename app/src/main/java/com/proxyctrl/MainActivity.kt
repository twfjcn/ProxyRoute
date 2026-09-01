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
            runSu("mkdir -p $targetDir")
            runSu("cp ${tempFile.absolutePath} $destPath")
            runSu("chmod 755 $destPath")
            tempFile.delete()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun isScriptInstalled(): Boolean {
        val ret = runSu("test -f $scriptProxy && echo ok")
        return ret == "ok"
    }

    private fun isRunning(): Boolean {
        val out = runSu("pgrep -f proxy.sh")
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
                runSu("sh $scriptProxy &")
                launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "已发送启动请求", Toast.LENGTH_SHORT).show()
                    refreshUi()
                }
            }
        }

        btnStop.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                runSu("sh $scriptStop")
                launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "已发送停止请求", Toast.LENGTH_SHORT).show()
                    refreshUi()
                }
            }
        }

        btnViewLog.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val logText = runSu("tail -n 40 $logFile")
                launch(Dispatchers.Main) {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("运行日志")
                        .setMessage(if(logText.isBlank())"日志为空" else logText)
                        .setPositiveButton("关闭",null)
                        .show()
                }
            }
        }
        refreshUi()
    }
}
