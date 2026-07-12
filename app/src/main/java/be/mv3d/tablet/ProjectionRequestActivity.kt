package be.mv3d.tablet

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

/**
 * Onzichtbare tussenactiviteit die de eenmalige MediaProjection-toestemming vraagt
 * ("Nu starten"). Android verplicht dat deze prompt vanuit een Activity komt — een
 * achtergronddienst mag geen scherm opnemen zonder deze stap. Na toestemming start
 * ze de ScreenCaptureService met het resultaat.
 */
class ProjectionRequestActivity : Activity() {
    private val REQ = 7001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ScreenCaptureService::class.java)
                .putExtra(ScreenCaptureService.EXTRA_RESULT, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        }
        finish()
    }
}
