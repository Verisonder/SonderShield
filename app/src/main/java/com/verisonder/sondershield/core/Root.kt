package com.verisonder.sondershield.core

import java.util.concurrent.TimeUnit

/**
 * Root mode: no VPN. iptables sends every DNS query on the phone (port 53, UDP and TCP,
 * IPv4 and IPv6) to the engine's local server. SonderShield's own queries and the bypass
 * apps keep their normal path.
 */
object Root {
    const val PORT = 5353
    private const val CHAIN = "SONDERSHIELD"

    /** Runs a script as root. Returns (exit code, output). */
    fun run(script: String, timeoutSec: Long = 15): Pair<Int, String> = try {
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use { it.write(script); it.write("\nexit\n") }
        val out = StringBuilder()
        val reader = Thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }.apply { start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroy()
            -1 to "timed out"
        } else {
            reader.join(1000)
            p.exitValue() to out.toString()
        }
    } catch (e: Exception) {
        -1 to (e.message ?: "su not found")
    }

    /** True when su works and grants uid 0 (the root manager may ask the user first). */
    fun available(): Boolean = run("id").let { (code, out) -> code == 0 && "uid=0" in out }

    /** Installs the redirect. [ownUid] and [bypassUids] keep their normal DNS. */
    fun apply(ownUid: Int, bypassUids: Collection<Int>): Pair<Int, String> {
        val s = StringBuilder()
        for (ipt in listOf("iptables", "ip6tables")) {
            s.append("$ipt -w -t nat -N $CHAIN 2>/dev/null\n")
            s.append("$ipt -w -t nat -F $CHAIN || exit 3\n")
            for (u in listOf(ownUid) + bypassUids) s.append("$ipt -w -t nat -A $CHAIN -m owner --uid-owner $u -j RETURN\n")
            s.append("$ipt -w -t nat -A $CHAIN -p udp --dport 53 -j REDIRECT --to-ports $PORT || exit 4\n")
            s.append("$ipt -w -t nat -A $CHAIN -p tcp --dport 53 -j REDIRECT --to-ports $PORT\n")
            s.append("$ipt -w -t nat -C OUTPUT -j $CHAIN 2>/dev/null || $ipt -w -t nat -I OUTPUT -j $CHAIN\n")
        }
        return run(s.toString())
    }

    /** Removes the redirect. Safe when it isn't there. */
    fun clear() {
        val s = StringBuilder()
        for (ipt in listOf("iptables", "ip6tables")) {
            s.append("while $ipt -w -t nat -D OUTPUT -j $CHAIN 2>/dev/null; do :; done\n")
            s.append("$ipt -w -t nat -F $CHAIN 2>/dev/null\n")
            s.append("$ipt -w -t nat -X $CHAIN 2>/dev/null\n")
        }
        run(s.toString())
    }
}
