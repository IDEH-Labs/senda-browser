package org.senda.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import java.io.File

/**
 * Opens Senda again in a new process. Gecko reads the proxy only when it starts, and it does not let an extension go
 * direct over that proxy (ProxyChannelFilter: "direct" keeps the default proxy): switching from Tor or a proxy to a
 * direct connection kept going through the proxy until Senda was closed. Runs in its own process (":restart"), waits
 * for the old one to end after saving the open tabs, and starts Senda again, which continues in the last tab.
 */
class SendaRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val oldPid = intent.getIntExtra(EXTRA_PID, -1)
        Thread {
            var waited = 0
            while (oldPid > 0 && File("/proc/$oldPid").exists() && waited < 5000) {
                Thread.sleep(100)
                waited += 100
            }
            if (oldPid > 0 && File("/proc/$oldPid").exists()) Process.killProcess(oldPid)
            runOnUiThread {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_RESUME, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                finish()
                Runtime.getRuntime().exit(0)
            }
        }.start()
    }

    companion object {
        const val EXTRA_PID = "org.senda.browser.RESTART_PID"
    }
}
