package ir.avang.vpn

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class ServerNode(
    val name: String,
    val host: String,
    val port: Int,
    val rawUri: String,
    var ping: Int = -1
)

data class SubMetadata(
    val title: String = "اشتراک V2Ray",
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireTimestamp: Long = 0L
)

class MainActivity : AppCompatActivity() {

    private lateinit var subInfoCard: LinearLayout
    private lateinit var tvSubTitle: TextView
    private lateinit var tvSubExpire: TextView
    private lateinit var pbTraffic: ProgressBar
    private lateinit var tvTrafficUsed: TextView
    private lateinit var tvTrafficRemain: TextView

    private lateinit var tvStatus: TextView
    private lateinit var tvServerInfo: TextView
    private lateinit var btnSelectServer: Button
    private lateinit var btnMagic: Button
    private lateinit var btnClipboard: Button
    private lateinit var btnQrScan: Button
    private lateinit var btnSubManager: Button
    private lateinit var btnPower: Button

    private var isConnected = false
    private var pulseAnim: ObjectAnimator? = null
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val subLinks = mutableListOf<String>()
    private val serverList = mutableListOf<ServerNode>()
    private var activeServer: ServerNode? = null
    private var subMeta: SubMetadata? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            activateVpnConnection()
        } else {
            Toast.makeText(this, "مجوز VPN صادر نشد", Toast.LENGTH_SHORT).show()
        }
    }

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleImportedText(result.contents.trim())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        subInfoCard = findViewById(R.id.subInfoCard)
        tvSubTitle = findViewById(R.id.tvSubTitle)
        tvSubExpire = findViewById(R.id.tvSubExpire)
        pbTraffic = findViewById(R.id.pbTraffic)
        tvTrafficUsed = findViewById(R.id.tvTrafficUsed)
        tvTrafficRemain = findViewById(R.id.tvTrafficRemain)

        tvStatus = findViewById(R.id.tvStatus)
        tvServerInfo = findViewById(R.id.tvServerInfo)
        btnSelectServer = findViewById(R.id.btnSelectServer)
        btnMagic = findViewById(R.id.btnMagic)
        btnClipboard = findViewById(R.id.btnClipboard)
        btnQrScan = findViewById(R.id.btnQrScan)
        btnSubManager = findViewById(R.id.btnSubManager)
        btnPower = findViewById(R.id.btnPower)

        loadData()
        refreshAllUI()

        btnClipboard.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (clipText.isNullOrEmpty()) {
                Toast.makeText(this, "کلیپ‌بورد خالی است!", Toast.LENGTH_SHORT).show()
            } else {
                handleImportedText(clipText)
            }
        }

        btnQrScan.setOnClickListener {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("کد QR کانفیگ یا ساب‌لینک را اسکن کنید")
                setBeepEnabled(true)
                setOrientationLocked(true)
            }
            qrLauncher.launch(options)
        }

        btnSelectServer.setOnClickListener {
            showManualServerPicker()
        }

        btnSubManager.setOnClickListener {
            showSubscriptionManagerDialog()
        }

        btnMagic.setOnClickListener {
            runMagicUpdateAndPing()
        }

        btnPower.setOnClickListener {
            if (!isConnected) {
                if (activeServer == null && serverList.isEmpty()) {
                    Toast.makeText(this, "ابتدا ساب‌لینک وارد کنید و دکمه آپدیت را بزنید", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (activeServer == null && serverList.isNotEmpty()) {
                    activeServer = serverList.first()
                }
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    vpnPermissionLauncher.launch(intent)
                } else {
                    activateVpnConnection()
                }
            } else {
                disconnectVpn()
            }
        }
    }

    private fun handleImportedText(text: String) {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var addedSubs = 0
        var addedConfigs = 0

        for (line in lines) {
            if (line.startsWith("http://") || line.startsWith("https://")) {
                if (!subLinks.contains(line)) {
                    subLinks.add(line)
                    addedSubs++
                }
            } else if (isV2RayUri(line)) {
                parseConfigUri(line)?.let {
                    serverList.add(it)
                    addedConfigs++
                }
            }
        }

        saveData()
        refreshAllUI()

        if (addedSubs > 0) {
            Toast.makeText(this, "ساب‌لینک اضافه شد. در حال دریافت اطلاعات و سرورها...", Toast.LENGTH_SHORT).show()
            runMagicUpdateAndPing()
        } else if (addedConfigs > 0) {
            Toast.makeText(this, "$addedConfigs کانفیگ اضافه شد. در حال تست پینگ...", Toast.LENGTH_SHORT).show()
            pingAndSelectBest()
        } else {
            Toast.makeText(this, "لینک یا کانفیگ معتبر یافت نشد!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runMagicUpdateAndPing() {
        if (subLinks.isEmpty() && serverList.isEmpty()) {
            Toast.makeText(this, "هیچ ساب‌لینکی ثبت نشده است!", Toast.LENGTH_SHORT).show()
            return
        }

        btnMagic.isEnabled = false
        tvServerInfo.text = "در حال دریافت سرورها و اطلاعات حجم اشتراک..."
        tvServerInfo.setTextColor(Color.parseColor("#C77DFF"))

        Executors.newSingleThreadExecutor().execute {
            if (subLinks.isNotEmpty()) {
                val fetchedServers = mutableListOf<ServerNode>()
                var latestMeta: SubMetadata? = null

                for (sub in subLinks) {
                    try {
                        val conn = (URL(sub).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 8000
                            readTimeout = 8000
                            setRequestProperty("User-Agent", "v2rayNG/1.8.5")
                        }
                        val userInfoHeader = conn.getHeaderField("subscription-userinfo")
                        val titleHeader = conn.getHeaderField("profile-title")
                        if (userInfoHeader != null || titleHeader != null) {
                            latestMeta = parseSubHeaders(userInfoHeader, titleHeader)
                        }

                        val raw = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                        val decoded = try {
                            String(Base64.decode(raw, Base64.DEFAULT))
                        } catch (e: Exception) {
                            raw
                        }
                        decoded.lines().map { it.trim() }.filter { isV2RayUri(it) }.forEach { uri ->
                            parseConfigUri(uri)?.let { fetchedServers.add(it) }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (latestMeta != null) {
                    subMeta = latestMeta
                }
                if (fetchedServers.isNotEmpty()) {
                    serverList.clear()
                    serverList.addAll(fetchedServers)
                }
                saveData()
            }
            mainHandler.post {
                refreshAllUI()
                pingAndSelectBest()
            }
        }
    }

    private fun parseSubHeaders(userInfo: String?, titleRaw: String?): SubMetadata {
        var title = "اشتراک فعال"
        if (!titleRaw.isNullOrBlank()) {
            title = if (titleRaw.startsWith("base64:")) {
                try {
                    String(Base64.decode(titleRaw.removePrefix("base64:"), Base64.DEFAULT))
                } catch (_: Exception) { titleRaw }
            } else {
                try { URLDecoder.decode(titleRaw, "UTF-8") } catch (_: Exception) { titleRaw }
            }
        }

        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L

        userInfo?.split(";")?.forEach { part ->
            val kv = part.trim().split("=")
            if (kv.size == 2) {
                val v = kv[1].trim().toLongOrNull() ?: 0L
                when (kv[0].trim().lowercase()) {
                    "upload" -> upload = v
                    "download" -> download = v
                    "total" -> total = v
                    "expire" -> expire = v
                }
            }
        }
        return SubMetadata(title, upload, download, total, expire)
    }

    private fun pingAndSelectBest() {
        if (serverList.isEmpty()) {
            btnMagic.isEnabled = true
            tvServerInfo.text = "سروری یافت نشد"
            return
        }

        tvServerInfo.text = "در حال تست پینگ موازی ${serverList.size} سرور..."

        Executors.newSingleThreadExecutor().execute {
            val pool = Executors.newFixedThreadPool(12)
            val tasks = serverList.map { node ->
                Callable {
                    node.ping = measureTcpPing(node.host, node.port)
                    node
                }
            }
            try {
                pool.invokeAll(tasks)
            } finally {
                pool.shutdown()
            }

            serverList.sortWith(compareBy({ if (it.ping > 0) 0 else 1 }, { it.ping }))
            val validServers = serverList.filter { it.ping in 1..4999 }

            mainHandler.post {
                btnMagic.isEnabled = true
                if (validServers.isNotEmpty()) {
                    activeServer = validServers.first()
                    saveData()
                    refreshAllUI()
                    Toast.makeText(this, "سریع‌ترین سرور انتخاب شد (${activeServer!!.ping}ms)", Toast.LENGTH_SHORT).show()
                    if (isConnected) {
                        activateVpnConnection()
                    }
                } else {
                    if (activeServer == null) activeServer = serverList.first()
                    saveData()
                    refreshAllUI()
                    Toast.makeText(this, "پینگ سرورها پاسخ نداد اما لیست آماده است", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showManualServerPicker() {
        if (serverList.isEmpty()) {
            Toast.makeText(this, "لیست سرورها خالی است! ابتدا ساب‌لینک را آپدیت کنید", Toast.LENGTH_SHORT).show()
            return
        }
        val items = serverList.map { node ->
            val pingBadge = if (node.ping > 0) "🟢 ${node.ping} ms" else "⚪ تست‌نشده"
            val activeMark = if (node.rawUri == activeServer?.rawUri) "✓ " else ""
            "$activeMark${node.name}\n$pingBadge"
        }.toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("انتخاب سرور (${serverList.size} کانفیگ)")
            .setItems(items) { _, which ->
                activeServer = serverList[which]
                saveData()
                refreshAllUI()
                Toast.makeText(this, "سرور انتخاب شد: ${activeServer!!.name}", Toast.LENGTH_SHORT).show()
                if (isConnected) {
                    activateVpnConnection()
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun measureTcpPing(host: String, port: Int): Int {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2500)
            }
            (System.currentTimeMillis() - start).toInt()
        } catch (e: Exception) {
            -1
        }
    }

    private fun isV2RayUri(uri: String): Boolean {
        return uri.startsWith("vless://") || uri.startsWith("vmess://") ||
               uri.startsWith("trojan://") || uri.startsWith("ss://")
    }

    private fun parseConfigUri(uri: String): ServerNode? {
        return try {
            if (uri.startsWith("vmess://")) {
                val b64 = uri.removePrefix("vmess://")
                val jsonStr = String(Base64.decode(b64, Base64.DEFAULT))
                val obj = gson.fromJson(jsonStr, JsonObject::class.java)
                val host = obj.get("add")?.asString ?: return null
                val port = obj.get("port")?.asInt ?: 443
                val ps = obj.get("ps")?.asString?.takeIf { it.isNotBlank() } ?: host
                ServerNode(ps, host, port, uri)
            } else {
                val parsed = Uri.parse(uri)
                val host = parsed.host ?: return null
                val port = if (parsed.port > 0) parsed.port else 443
                val tag = parsed.fragment?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() } ?: host
                ServerNode(tag, host, port, uri)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun showSubscriptionManagerDialog() {
        if (subLinks.isEmpty()) {
            Toast.makeText(this, "لیست ساب‌لینک‌ها خالی است", Toast.LENGTH_SHORT).show()
            return
        }
        val displayItems = subLinks.mapIndexed { index, url ->
            val shortUrl = if (url.length > 42) url.take(39) + "..." else url
            "${index + 1}. $shortUrl"
        }.toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("حذف ساب‌لینک (روی مورد بزنید)")
            .setItems(displayItems) { _, which ->
                subLinks.removeAt(which)
                if (subLinks.isEmpty()) {
                    serverList.clear()
                    activeServer = null
                    subMeta = null
                    tvServerInfo.text = "سروری انتخاب نشده است"
                }
                saveData()
                refreshAllUI()
                Toast.makeText(this, "ساب‌لینک با موفقیت حذف شد", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun activateVpnConnection() {
        val target = activeServer ?: return
        val xrayJson = XrayConfigBuilder.buildFullXrayJson(target.rawUri)
        if (xrayJson == null) {
            Toast.makeText(this, "خطا در پردازش ساختار این کانفیگ!", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, AvangVpnService::class.java).apply {
            putExtra("XRAY_JSON", xrayJson)
            putExtra("SERVER_NAME", target.name)
        }
        startService(intent)
        isConnected = true

        tvStatus.text = "CONNECTED • کانتکت شد"
        tvStatus.setTextColor(Color.parseColor("#00E676"))
        btnPower.text = "CONNECTED"
        btnPower.setBackgroundResource(R.drawable.bg_power_on)
        startPulseAnimation()
    }

    private fun disconnectVpn() {
        val intent = Intent(this, AvangVpnService::class.java).apply { action = "STOP" }
        startService(intent)
        isConnected = false

        tvStatus.text = "DISCONNECTED"
        tvStatus.setTextColor(Color.parseColor("#FF5252"))
        btnPower.text = "CONNECT"
        btnPower.setBackgroundResource(R.drawable.bg_power_off)
        stopPulseAnimation()
    }

    private fun startPulseAnimation() {
        stopPulseAnimation()
        val scaleX = PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.07f)
        val scaleY = PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.07f)
        pulseAnim = ObjectAnimator.ofPropertyValuesHolder(btnPower, scaleX, scaleY).apply {
            duration = 950
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnim?.cancel()
        pulseAnim = null
        btnPower.scaleX = 1.0f
        btnPower.scaleY = 1.0f
    }

    private fun refreshAllUI() {
        btnSubManager.text = "🗑 ساب‌ها (${subLinks.size})"
        btnSelectServer.text = "🌐 مشاهده و انتخاب دستی سرورها (${serverList.size})"

        activeServer?.let {
            val pingTxt = if (it.ping > 0) " (${it.ping} ms)" else ""
            tvServerInfo.text = "⚡ ${it.name}$pingTxt"
            tvServerInfo.setTextColor(Color.parseColor("#FFFFFF"))
        }

        if (subMeta != null) {
            val meta = subMeta!!
            subInfoCard.visibility = View.VISIBLE
            tvSubTitle.text = "💎 ${meta.title}"

            val usedBytes = meta.uploadBytes + meta.downloadBytes
            val usedGb = usedBytes / (1024.0 * 1024.0 * 1024.0)
            tvTrafficUsed.text = String.format(Locale.US, "مصرف: %.2f GB", usedGb)

            if (meta.totalBytes > 0) {
                val totalGb = meta.totalBytes / (1024.0 * 1024.0 * 1024.0)
                val remainGb = ((meta.totalBytes - usedBytes).coerceAtLeast(0L)) / (1024.0 * 1024.0 * 1024.0)
                val percent = ((usedBytes * 100) / meta.totalBytes).toInt().coerceIn(0, 100)
                pbTraffic.progress = percent
                tvTrafficRemain.text = String.format(Locale.US, "باقی‌مانده: %.2f از %.1f GB", remainGb, totalGb)
            } else {
                pbTraffic.progress = 0
                tvTrafficRemain.text = "حجم: نامحدود"
            }

            if (meta.expireTimestamp > 0) {
                val nowSec = System.currentTimeMillis() / 1000L
                val daysLeft = ((meta.expireTimestamp - nowSec) / 86400L)
                val dateStr = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date(meta.expireTimestamp * 1000L))
                tvSubExpire.text = if (daysLeft >= 0) "اعتبار: $daysLeft روز ($dateStr)" else "منقضی شده ($dateStr)"
            } else {
                tvSubExpire.text = "اعتبار: نامحدود"
            }
        } else {
            subInfoCard.visibility = View.GONE
        }
    }

    private fun saveData() {
        val prefs = getSharedPreferences("avang_vpn_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("subs", gson.toJson(subLinks))
            .putString("servers", gson.toJson(serverList))
            .putString("active", gson.toJson(activeServer))
            .putString("meta", gson.toJson(subMeta))
            .apply()
    }

    private fun loadData() {
        val prefs = getSharedPreferences("avang_vpn_prefs", Context.MODE_PRIVATE)
        prefs.getString("subs", null)?.let {
            val type = object : TypeToken<MutableList<String>>() {}.type
            subLinks.clear()
            subLinks.addAll(gson.fromJson(it, type))
        }
        prefs.getString("servers", null)?.let {
            val type = object : TypeToken<MutableList<ServerNode>>() {}.type
            serverList.clear()
            serverList.addAll(gson.fromJson(it, type))
        }
        prefs.getString("active", null)?.let {
            activeServer = gson.fromJson(it, ServerNode::class.java)
        }
        prefs.getString("meta", null)?.let {
            subMeta = gson.fromJson(it, SubMetadata::class.java)
        }
    }
}