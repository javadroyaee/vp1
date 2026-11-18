package ir.avang.vpn

import android.net.Uri
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URLDecoder

object XrayConfigBuilder {

    private val gson = Gson()

    fun buildFullXrayJson(rawUri: String, socksPort: Int = 10808): String? {
        val outbound = when {
            rawUri.startsWith("vless://") -> parseVless(rawUri)
            rawUri.startsWith("vmess://") -> parseVmess(rawUri)
            rawUri.startsWith("trojan://") -> parseTrojan(rawUri)
            rawUri.startsWith("ss://") -> parseShadowsocks(rawUri)
            else -> null
        } ?: return null

        val root = JsonObject()
        root.add("log", JsonObject().apply { addProperty("loglevel", "warning") })

        val dns = JsonObject().apply {
            val servers = JsonArray().apply {
                add("1.1.1.1")
                add("8.8.8.8")
            }
            add("servers", servers)
        }
        root.add("dns", dns)

        val inbounds = JsonArray()
        val socksInbound = JsonObject().apply {
            addProperty("tag", "socks")
            addProperty("port", socksPort)
            addProperty("listen", "127.0.0.1")
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("auth", "noauth")
                addProperty("udp", true)
            })
            add("sniffing", JsonObject().apply {
                addProperty("enabled", true)
                add("destOverride", JsonArray().apply { add("http"); add("tls") })
            })
        }
        inbounds.add(socksInbound)
        root.add("inbounds", inbounds)

        val outbounds = JsonArray()
        outbounds.add(outbound)
        outbounds.add(JsonObject().apply {
            addProperty("tag", "direct")
            addProperty("protocol", "freedom")
        })
        outbounds.add(JsonObject().apply {
            addProperty("tag", "block")
            addProperty("protocol", "blackhole")
        })
        root.add("outbounds", outbounds)

        return gson.toJson(root)
    }

    private fun parseVless(uriStr: String): JsonObject? {
        return try {
            val uri = Uri.parse(uriStr)
            val uuid = uri.userInfo ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443

            val outbound = JsonObject().apply {
                addProperty("tag", "proxy")
                addProperty("protocol", "vless")
            }

            val user = JsonObject().apply {
                addProperty("id", uuid)
                addProperty("encryption", uri.getQueryParameter("encryption") ?: "none")
                uri.getQueryParameter("flow")?.takeIf { it.isNotEmpty() }?.let { addProperty("flow", it) }
            }

            val vnextItem = JsonObject().apply {
                addProperty("address", host)
                addProperty("port", port)
                add("users", JsonArray().apply { add(user) })
            }

            outbound.add("settings", JsonObject().apply {
                add("vnext", JsonArray().apply { add(vnextItem) })
            })

            outbound.add("streamSettings", buildStreamSettings(
                net = uri.getQueryParameter("type") ?: "tcp",
                security = uri.getQueryParameter("security") ?: "none",
                sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer") ?: host,
                fp = uri.getQueryParameter("fp") ?: "chrome",
                pbk = uri.getQueryParameter("pbk") ?: "",
                sid = uri.getQueryParameter("sid") ?: "",
                path = uri.getQueryParameter("path") ?: "/",
                hostHeader = uri.getQueryParameter("host") ?: host,
                serviceName = uri.getQueryParameter("serviceName") ?: ""
            ))
            outbound
        } catch (e: Exception) { null }
    }

    private fun parseVmess(uriStr: String): JsonObject? {
        return try {
            val b64 = uriStr.removePrefix("vmess://")
            val jsonStr = String(Base64.decode(b64, Base64.DEFAULT))
            val obj = gson.fromJson(jsonStr, JsonObject::class.java)

            val host = obj.get("add")?.asString ?: return null
            val port = obj.get("port")?.asInt ?: 443
            val uuid = obj.get("id")?.asString ?: return null
            val aid = obj.get("aid")?.asInt ?: 0
            val net = obj.get("net")?.asString ?: "tcp"
            val tls = obj.get("tls")?.asString ?: ""
            val sni = obj.get("sni")?.asString?.takeIf { it.isNotEmpty() } ?: obj.get("host")?.asString ?: host
            val path = obj.get("path")?.asString ?: "/"
            val hostHeader = obj.get("host")?.asString ?: host

            val outbound = JsonObject().apply {
                addProperty("tag", "proxy")
                addProperty("protocol", "vmess")
            }

            val user = JsonObject().apply {
                addProperty("id", uuid)
                addProperty("alterId", aid)
                addProperty("security", obj.get("scy")?.asString ?: "auto")
            }

            val vnextItem = JsonObject().apply {
                addProperty("address", host)
                addProperty("port", port)
                add("users", JsonArray().apply { add(user) })
            }

            outbound.add("settings", JsonObject().apply {
                add("vnext", JsonArray().apply { add(vnextItem) })
            })

            outbound.add("streamSettings", buildStreamSettings(
                net = net,
                security = if (tls == "tls") "tls" else "none",
                sni = sni,
                fp = obj.get("fp")?.asString ?: "chrome",
                pbk = "",
                sid = "",
                path = path,
                hostHeader = hostHeader,
                serviceName = path
            ))
            outbound
        } catch (e: Exception) { null }
    }

    private fun parseTrojan(uriStr: String): JsonObject? {
        return try {
            val uri = Uri.parse(uriStr)
            val password = uri.userInfo ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443

            val outbound = JsonObject().apply {
                addProperty("tag", "proxy")
                addProperty("protocol", "trojan")
            }

            val serverItem = JsonObject().apply {
                addProperty("address", host)
                addProperty("port", port)
                addProperty("password", password)
            }

            outbound.add("settings", JsonObject().apply {
                add("servers", JsonArray().apply { add(serverItem) })
            })

            outbound.add("streamSettings", buildStreamSettings(
                net = uri.getQueryParameter("type") ?: "tcp",
                security = uri.getQueryParameter("security") ?: "tls",
                sni = uri.getQueryParameter("sni") ?: host,
                fp = uri.getQueryParameter("fp") ?: "chrome",
                pbk = uri.getQueryParameter("pbk") ?: "",
                sid = uri.getQueryParameter("sid") ?: "",
                path = uri.getQueryParameter("path") ?: "/",
                hostHeader = uri.getQueryParameter("host") ?: host,
                serviceName = uri.getQueryParameter("serviceName") ?: ""
            ))
            outbound
        } catch (e: Exception) { null }
    }

    private fun parseShadowsocks(uriStr: String): JsonObject? {
        return try {
            val uri = Uri.parse(uriStr)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 8388
            val userInfo = uri.userInfo ?: return null
            val decoded = if (userInfo.contains(":")) userInfo else String(Base64.decode(userInfo, Base64.URL_SAFE or Base64.NO_WRAP))
            val parts = decoded.split(":", limit = 2)
            if (parts.size < 2) return null

            val outbound = JsonObject().apply {
                addProperty("tag", "proxy")
                addProperty("protocol", "shadowsocks")
            }
            val serverItem = JsonObject().apply {
                addProperty("address", host)
                addProperty("port", port)
                addProperty("method", parts[0])
                addProperty("password", parts[1])
            }
            outbound.add("settings", JsonObject().apply {
                add("servers", JsonArray().apply { add(serverItem) })
            })
            outbound
        } catch (e: Exception) { null }
    }

    private fun buildStreamSettings(
        net: String, security: String, sni: String, fp: String,
        pbk: String, sid: String, path: String, hostHeader: String, serviceName: String
    ): JsonObject {
        val stream = JsonObject().apply {
            addProperty("network", net)
            addProperty("security", security)
        }

        if (security == "tls") {
            stream.add("tlsSettings", JsonObject().apply {
                addProperty("serverName", sni)
                addProperty("allowInsecure", true)
                addProperty("fingerprint", fp)
            })
        } else if (security == "reality") {
            stream.add("realitySettings", JsonObject().apply {
                addProperty("serverName", sni)
                addProperty("fingerprint", fp)
                addProperty("publicKey", pbk)
                addProperty("shortId", sid)
                addProperty("spiderX", "/")
            })
        }

        if (net == "ws") {
            stream.add("wsSettings", JsonObject().apply {
                addProperty("path", URLDecoder.decode(path, "UTF-8"))
                add("headers", JsonObject().apply { addProperty("Host", hostHeader) })
            })
        } else if (net == "grpc") {
            stream.add("grpcSettings", JsonObject().apply {
                addProperty("serviceName", serviceName)
            })
        }
        return stream
    }
}