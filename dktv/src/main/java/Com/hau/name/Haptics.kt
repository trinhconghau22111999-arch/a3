package Com.hau.name

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Rung ngan khi cham phim. Dung truc tiep Vibrator (quyen VIBRATE) nen van rung ke ca khi
 * "rung khi cham" trong cai dat he thong dang tat - khac voi performHapticFeedback().
 */
object Haptics {
    private const val TICK_MS = 25L

    @Suppress("DEPRECATION")
    fun tick(view: View) {
        val vib = view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vib != null && vib.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(TICK_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vib.vibrate(TICK_MS)
            }
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
}
