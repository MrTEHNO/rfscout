package ua.sem.rfscout.scanners

import android.content.Context
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.CellIdentityNr
import android.telephony.TelephonyManager
import ua.sem.rfscout.Target

/**
 * Стільникові: сервісна та сусідні соти, RSRP/RSSI та ARFCN.
 * Оновлення приблизно раз на секунду, пеленгувати можна тільки
 * сервісну соту з достатньою динамікою сигналу.
 */
class CellScanner(private val ctx: Context) {

    private val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    @Suppress("DEPRECATION")
    fun read(): List<Target> {
        val out = ArrayList<Target>()
        val infos: List<CellInfo> = runCatching { tm.allCellInfo ?: emptyList() }
            .getOrDefault(emptyList())

        for (c in infos) {
            when {
                c is CellInfoLte -> {
                    val id = c.cellIdentity
                    val ss = c.cellSignalStrength
                    out.add(
                        Target(
                            id = "lte-${id.ci}-${id.pci}",
                            label = "LTE PCI ${id.pci}",
                            freqMhz = 0,
                            rssi = ss.dbm,
                            extra = "EARFCN ${id.earfcn} · RSRQ ${ss.rsrq}" +
                                    (if (c.isRegistered) " · serving" else "")
                        )
                    )
                }
                Build.VERSION.SDK_INT >= 29 && c is CellInfoNr -> {
                    val id = c.cellIdentity as? CellIdentityNr
                    val ss = c.cellSignalStrength as? CellSignalStrengthNr
                    out.add(
                        Target(
                            id = "nr-${id?.pci}",
                            label = "5G NR PCI ${id?.pci}",
                            freqMhz = 0,
                            rssi = ss?.dbm ?: -140,
                            extra = "NRARFCN ${id?.nrarfcn}" +
                                    (if (c.isRegistered) " · serving" else "")
                        )
                    )
                }
                c is CellInfoWcdma -> {
                    val id = c.cellIdentity
                    out.add(
                        Target(
                            id = "wcdma-${id.cid}",
                            label = "WCDMA ${id.cid}",
                            freqMhz = 0,
                            rssi = c.cellSignalStrength.dbm,
                            extra = "UARFCN ${id.uarfcn}"
                        )
                    )
                }
                c is CellInfoGsm -> {
                    val id = c.cellIdentity
                    out.add(
                        Target(
                            id = "gsm-${id.cid}",
                            label = "GSM ${id.cid}",
                            freqMhz = 0,
                            rssi = c.cellSignalStrength.dbm,
                            extra = "ARFCN ${id.arfcn}"
                        )
                    )
                }
            }
        }
        return out.sortedByDescending { it.rssi }
    }
}
