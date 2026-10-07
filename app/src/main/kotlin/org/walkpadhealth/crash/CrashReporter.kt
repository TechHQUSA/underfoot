package org.walkpadhealth.crash

import android.app.Application
import android.content.Context
import org.walkpadhealth.AppPrefs
import org.walkpadhealth.BuildConfig
import java.io.File

object CrashReporter {
    /** Only class names, method names and line numbers. Exception messages are dropped because they can hold paths or user data. */
    fun render(t: Throwable, versionName: String, sdk: Int): String = buildString {
        append("Walkpad Health $versionName, SDK $sdk\n\n")
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth++ < 5) {
            append(c.javaClass.name).append('\n')
            c.stackTrace.take(40).forEach { append("  at ").append(it.className).append('.').append(it.methodName).append(':').append(it.lineNumber).append('\n') }
            c = c.cause
        }
    }

    private fun file(ctx: Context) = File(File(ctx.filesDir, "crash").apply { mkdirs() }, "last.txt")

    fun pending(ctx: Context): File? = file(ctx).takeIf { it.exists() && it.length() > 0 }
    fun discard(ctx: Context) { file(ctx).delete() }

    private fun offered(ctx: Context) = File(file(ctx).parentFile, "offered.txt")

    /** Called when the user taps Send: the file moves aside so it is not prompted again but stays readable for the share sheet. */
    fun markOffered(ctx: Context): File {
        val dst = offered(ctx).also { it.delete() }
        if (!file(ctx).renameTo(dst)) { file(ctx).copyTo(dst, overwrite = true); file(ctx).delete() }
        dst.setLastModified(System.currentTimeMillis())          // a rename keeps the old time; retention counts from the offer
        return dst
    }

    /** Offered files are kept 24 hours so a slow share target can still read them; never deleted merely because the app started. */
    fun cleanOffered(ctx: Context) {
        val f = offered(ctx)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() > 24 * 3_600_000L) f.delete()
    }

    fun install(app: Application) {
        cleanOffered(app)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            try {
                if (AppPrefs(app).crashOffer) file(app).writeText(render(e, BuildConfig.VERSION_NAME, android.os.Build.VERSION.SDK_INT))
            } catch (_: Exception) { /* never mask the original crash */ }
            prev?.uncaughtException(th, e)
        }
    }
}
