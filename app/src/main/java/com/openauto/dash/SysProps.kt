package com.openauto.dash

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.IBinder
import com.openauto.unitproto.UnitProbe
import java.io.File

/**
 * The one place the hidden system classes are reached by reflection: the
 * system properties the firmwares talk through, and the ServiceManager that
 * holds their binders. Every reader of a vendor property goes through here.
 */
object SysProps {
    /** The property's value ("" when unset), or null when the class can't be reached at all. */
    @SuppressLint("PrivateApi")
    fun get(name: String): String? = runCatching { getter?.invoke(null, name) as String? }.getOrNull()

    /** Looked up once: several feeds read a property every second. */
    @delegate:SuppressLint("PrivateApi")
    private val getter: java.lang.reflect.Method? by lazy {
        runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) }.getOrNull()
    }

    /** The property's value, or null when unset or blank. */
    fun getOrNull(name: String): String? = get(name)?.takeIf { it.isNotBlank() }

    /** Whether a system service of that name is registered, without waiting for it as getService does. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun hasService(name: String): Boolean = runCatching {
        Class.forName("android.os.ServiceManager").getMethod("checkService", String::class.java).invoke(null, name) as IBinder?
    }.getOrNull() != null

    /** The system service's binder, or null (a system class apps may reach by reflection). */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun service(name: String): IBinder? = runCatching {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as IBinder?
    }.getOrNull()
}

/** The unit as [com.openauto.unitproto.UnitDetector] sees it: properties, packages, binders, classes, files, build fields. */
internal class AndroidUnitProbe(context: Context) : UnitProbe {
    private val pm = context.applicationContext.packageManager

    override fun prop(name: String): String? = SysProps.getOrNull(name)
    override fun hasPackage(pkg: String): Boolean = isPackageInstalled(pm, pkg)
    override fun hasService(name: String): Boolean = SysProps.hasService(name)
    override fun hasClass(name: String): Boolean = runCatching { Class.forName(name); true }.getOrDefault(false)
    override fun exists(path: String): Boolean = runCatching { File(path).exists() }.getOrDefault(false)
    override val sdkInt: Int get() = Build.VERSION.SDK_INT
    override val brand: String get() = Build.BRAND.orEmpty()
    override val device: String get() = Build.DEVICE.orEmpty()
    override val model: String get() = Build.MODEL.orEmpty()
    override val display: String get() = Build.DISPLAY.orEmpty()
}
