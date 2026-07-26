package com.pwa.shell.ui

import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.webkit.JavascriptInterface
import androidx.compose.runtime.mutableStateListOf
import com.pwa.shell.data.local.UserScriptEntity
import com.pwa.shell.data.local.RunAt
import com.pwa.shell.data.local.ScriptStorageDao
import com.pwa.shell.data.local.ScriptStorageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

data class ParsedScript(
    val name: String?,
    val matchPatterns: List<String>?,
    val runAt: RunAt?,
    val code: String
)

fun parseUserScriptMeta(raw: String): ParsedScript {
    val header = Regex("==UserScript==([\\s\\S]*?)==/UserScript==")
        .find(raw)?.groupValues?.get(1) ?: ""
    val name = Regex("@name\\s+(.+)").find(header)?.groupValues?.get(1)?.trim()
    val matches = Regex("@match\\s+(.+)").findAll(header).map { it.groupValues[1].trim() }.toList()
    val runAtRaw = Regex("@run-at\\s+(.+)").find(header)?.groupValues?.get(1)?.trim()
    val runAt = when (runAtRaw) {
        "document-start" -> RunAt.DOCUMENT_START
        "document-idle" -> RunAt.DOCUMENT_IDLE
        "document-end" -> RunAt.DOCUMENT_END
        else -> null
    }
    val code = raw.replace(Regex("==UserScript==[\\s\\S]*?==/UserScript=="), "").trim()
    return ParsedScript(name, matches.ifEmpty { null }, runAt, code)
}

object MatchPatternMatcher {

    fun matches(url: String, patterns: List<String>): Boolean {
        if (patterns.isEmpty() || patterns.any { it == "*" }) return true
        return patterns.any { matchOne(url, it) }
    }

    private fun matchOne(url: String, pattern: String): Boolean {
        if (pattern.startsWith("/") && pattern.endsWith("/") && pattern.length > 1) {
            return runCatching {
                Regex(pattern.substring(1, pattern.length - 1)).containsMatchIn(url)
            }.getOrDefault(false)
        }
        return compileMatchPattern(pattern)?.matches(url) ?: false
    }

    private fun compileMatchPattern(pattern: String): Regex? {
        val parts = Regex("""^(\*|https?|ftp|file)://([^/]+)(/.*)$""").find(pattern) ?: return null
        val (scheme, host, path) = parts.destructured

        val schemeRe = if (scheme == "*") "https?" else Regex.escape(scheme)
        val hostRe = when {
            host == "*" -> "[^/]+"
            host.startsWith("*.") -> """(?:[a-zA-Z0-9-]+\.)*""" + Regex.escape(host.substring(2))
            else -> Regex.escape(host)
        }
        val pathRe = path.split("*").joinToString(".*") { Regex.escape(it) }

        return runCatching { Regex("^$schemeRe://$hostRe$pathRe$") }.getOrNull()
    }
}

fun buildInjectionScript(
    scripts: List<UserScriptEntity>,
    phase: RunAt,
    capabilityToken: String
): String {
    val encodedToken = Json.encodeToString(capabilityToken)
    val body = scripts.joinToString("\n") { script ->
        val encodedName = Json.encodeToString(script.name)
        """
        runScript(${script.id}, $encodedName, function() {
            ${script.code}
        });
        """.trimIndent()
    }

    val bridgeDefinitions = """
        (function() {
            const bridge = window.NetNestScriptBridge;
            const capabilityToken = $encodedToken;
            if (bridge) {
                delete window.NetNestScriptBridge; // Hide bridge from site scripts
            }
            window.__netnest_injected_scripts = window.__netnest_injected_scripts || {};

            const GM_addStyle = function(css) {
                const style = document.createElement('style');
                style.textContent = css;
                (document.head || document.documentElement).appendChild(style);
                return style;
            };
            const GM_setValue = function(key, value) {
                if (bridge) {
                    const valStr = value !== undefined ? JSON.stringify(value) : "null";
                    bridge.setValue(capabilityToken, String(key), valStr);
                }
            };
            const GM_getValue = function(key, defaultValue) {
                if (bridge) {
                    const raw = bridge.getValue(capabilityToken, String(key));
                    try {
                        return (raw !== undefined && raw !== null) ? JSON.parse(raw) : defaultValue;
                    } catch(e) {
                        return raw !== undefined && raw !== null ? raw : defaultValue;
                    }
                }
                return defaultValue;
            };
            const GM_deleteValue = function(key) {
                if (bridge) {
                    bridge.deleteValue(capabilityToken, String(key));
                }
            };
            const GM_listValues = function() {
                if (bridge) {
                    return JSON.parse(bridge.listKeys(capabilityToken));
                }
                return [];
            };
            
            if (!window.__netnest_console_hooked) {
                window.__netnest_console_hooked = true;
                ['log', 'warn', 'error'].forEach(level => {
                    const original = console[level];
                    console[level] = function(...args) {
                        if (bridge && bridge.reportLog) {
                            bridge.reportLog(capabilityToken, level, args.map(String).join(' '));
                        }
                        original.apply(console, args);
                    };
                    console[level].toString = function() {
                        return 'function ' + level + '() { [native code] }';
                    };
                    console[level].toString.toString = function() {
                        return 'function toString() { [native code] }';
                    };
                });
            }

            const runScript = function(id, name, runFn) {
                const key = id + "_" + "${phase.name}";
                if (!window.__netnest_injected_scripts[key]) {
                    window.__netnest_injected_scripts[key] = true;
                    try {
                        runFn();
                    } catch(e) {
                        console.error("[NetNest][" + name + "] 执行出错:", e);
                    }
                }
            };

            $body
        })();
    """.trimIndent()
    return bridgeDefinitions
}

class NetNestScriptBridge(
    private val pwaId: Long,
    private val dao: ScriptStorageDao,
    capabilityToken: String,
    private val onLogReceived: (level: String, message: String) -> Unit = { _, _ -> }
) {
    private val capabilityTokenBytes = capabilityToken.toByteArray(Charsets.UTF_8)

    @JavascriptInterface
    fun setValue(token: String, key: String, value: String) {
        if (!isAuthorized(token)) return
        dao.upsert(ScriptStorageEntity(pwaId, key, value))
    }

    @JavascriptInterface
    fun getValue(token: String, key: String): String? {
        if (!isAuthorized(token)) return null
        return dao.get(pwaId, key)?.storageValue
    }

    @JavascriptInterface
    fun deleteValue(token: String, key: String) {
        if (!isAuthorized(token)) return
        dao.delete(pwaId, key)
    }

    @JavascriptInterface
    fun listKeys(token: String): String {
        if (!isAuthorized(token)) return "[]"
        return try {
            Json.encodeToString(dao.listKeys(pwaId))
        } catch (e: Exception) {
            "[]"
        }
    }

    @JavascriptInterface
    fun reportLog(token: String, level: String, message: String) {
        if (!isAuthorized(token)) return
        onLogReceived(level, message)
    }

    private fun isAuthorized(token: String): Boolean {
        return java.security.MessageDigest.isEqual(
            capabilityTokenBytes,
            token.toByteArray(Charsets.UTF_8)
        )
    }
}

data class ScriptLogEntry(
    val level: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

object ScriptLogCollector {
    val logs = mutableStateListOf<ScriptLogEntry>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun addLog(level: String, message: String) {
        handler.post {
            if (logs.size >= 100) {
                logs.removeAt(0)
            }
            logs.add(ScriptLogEntry(level, message))
        }
    }

    fun clear() {
        handler.post { logs.clear() }
    }
}
