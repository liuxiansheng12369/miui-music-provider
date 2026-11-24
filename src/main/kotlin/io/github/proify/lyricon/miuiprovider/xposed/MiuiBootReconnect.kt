package io.github.proify.lyricon.miuiprovider.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.proify.lyricon.provider.LyriconProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 独立包版本的 SystemUI 重启自动重连。
 *
 * 与聚合版 app 里的 CentralBootReconnect 行为一致，但不依赖聚合版的 B1 保活配置
 * （独立包不安装 B1ConfigProvider，也不读 V3 的 su 配置文件），改为用本进程内的
 * 播放活跃标记判断是否需要重连：
 *  - 播放中的源进程收到 central boot 广播 -> 重注册 + 反射 syncs + 延迟重推当前歌；
 *  - 没在播放（暂停且已失活）的进程跳过，避免多个音乐源同时抢 central。
 */
object MiuiBootReconnect {

    private const val TAG = "MiuiBootReconnect"

    private const val ACTION_CENTRAL_BOOT_COMPLETED =
        "io.github.proify.lyricon.lyric.bridge.CENTRAL_BOOT_COMPLETED"

    private val providers = CopyOnWriteArraySet<LyriconProvider>()

    fun interface SongRepusher {
        fun repush()
    }

    private val repushers = CopyOnWriteArrayList<SongRepusher>()

    @Volatile
    private var playbackActive = false

    @Volatile
    private var receiverRegistered = false

    private val lock = Any()

    fun registerSongRepusher(r: SongRepusher) {
        repushers.add(r)
    }

    /** 播放状态变化时由 MiuiMusic 调用：只在播放中才参与 boot 重连。 */
    fun markPlaybackActive(active: Boolean) {
        playbackActive = active
    }

    fun attach(context: Context, provider: LyriconProvider) {
        providers.add(provider)
        if (receiverRegistered) return
        synchronized(lock) {
            if (receiverRegistered) return
            receiverRegistered = true
            runCatching {
                context.applicationContext.registerReceiver(
                    receiver,
                    IntentFilter(ACTION_CENTRAL_BOOT_COMPLETED)
                )
                Log.i(TAG, "boot broadcast receiver registered")
            }.onFailure {
                Log.e(TAG, "register receiver failed: ${it.message}")
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CENTRAL_BOOT_COMPLETED) return
            if (!playbackActive) {
                Log.d(TAG, "boot -> skip, not playing in this process")
                return
            }
            Log.i(TAG, "boot -> reconnecting ${providers.size} provider(s)")
            providers.forEach { p ->
                runCatching {
                    p.unregister()
                    p.register()
                    // syncs() 是远程库 internal 方法，编译后被 Kotlin mangling 成 syncs$provider，
                    // 这里遍历类层级反射调用，把当前歌重同步给 central
                    val player = p.player
                    if (player != null) {
                        runCatching {
                            var c: Class<*>? = player.javaClass
                            var m: java.lang.reflect.Method? = null
                            while (c != null && m == null) {
                                m = c.declaredMethods.firstOrNull {
                                    it.name.startsWith("syncs") && it.parameterCount == 0
                                }
                                if (m == null) c = c.superclass
                            }
                            if (m != null) {
                                m.isAccessible = true
                                m.invoke(player)
                                Log.i(TAG, "syncs invoked: ${m.declaringClass.name}.${m.name}")
                            }
                        }.onFailure { e ->
                            Log.e(TAG, "syncs via reflection failed: ${e.message}")
                        }
                    }
                }.onFailure { e ->
                    Log.e(TAG, "reconnect failed: ${e.message}")
                }
            }
            // 状态栏就绪有延迟，多次重推当前歌，第一次重启即可恢复
            longArrayOf(1500L, 3500L, 7000L).forEach { d ->
                runCatching {
                    Handler(Looper.getMainLooper()).postDelayed({
                        runCatching {
                            repushers.forEach { it.repush() }
                            Log.i(TAG, "boot -> re-pushed (delay=${d}ms)")
                        }
                    }, d)
                }
            }
        }
    }
}
