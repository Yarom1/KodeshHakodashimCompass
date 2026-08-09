package com.yarom.kodeshcompass

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var webView: WebView
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private val LOCATION_PERMISSION_REQUEST = 1001

    private val rotationMatrix = FloatArray(9)
    private val screenAdjustedMatrix = FloatArray(9)
    private val verticalRemapMatrix = FloatArray(9)
    private val orientationFlat = FloatArray(3)
    private val orientationVertical = FloatArray(3)
    private var lastSentHeading = -999f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.setGeolocationEnabled(true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            webView.loadUrl("file:///android_asset/index.html")
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            webView.loadUrl("file:///android_asset/index.html")
        }
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    // הפרש הזווית הקצר ביותר בין שתי זוויות (מונע קפיצת 360/0 בערבוב)
    private fun shortestDelta(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d < -180f) d += 360f
        if (d > 180f) d -= 360f
        return d
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        val rotation = windowManager.defaultDisplay.rotation
        val (axisX, axisY) = when (rotation) {
            Surface.ROTATION_90 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
            Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
            Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
            else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
        }
        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, screenAdjustedMatrix)
        SensorManager.getOrientation(screenAdjustedMatrix, orientationFlat)

        val pitchDeg = Math.toDegrees(orientationFlat[1].toDouble()).toFloat()
        var flatHeading = Math.toDegrees(orientationFlat[0].toDouble()).toFloat()
        if (flatHeading < 0) flatHeading += 360f

        // נוסחת "אנכי" - עוקבים אחרי ציר ה-Z השלילי (הכיוון שהגב מצביע אליו, לא החזית),
        // כי זה הציר שנשאר יציב ואופקי כשהמכשיר עומד, בניגוד ל-Y שנכנס לנקודת יחיד
        SensorManager.remapCoordinateSystem(
            screenAdjustedMatrix, SensorManager.AXIS_X, SensorManager.AXIS_MINUS_Z, verticalRemapMatrix
        )
        SensorManager.getOrientation(verticalRemapMatrix, orientationVertical)
        var verticalHeading = Math.toDegrees(orientationVertical[0].toDouble()).toFloat()
        if (verticalHeading < 0) verticalHeading += 360f

        // מעבר חלק בין הנוסחאות לפי זווית ההטיה - בלי קפיצה חדה בגבול
        val absPitch = abs(pitchDeg)
        val heading: Float = when {
            absPitch <= 50f -> flatHeading
            absPitch >= 65f -> verticalHeading
            else -> {
                val weight = (absPitch - 50f) / 15f // 0..1
                val delta = shortestDelta(flatHeading, verticalHeading)
                var blended = flatHeading + delta * weight
                blended = ((blended % 360f) + 360f) % 360f
                blended
            }
        }

        if (Math.abs(shortestDelta(lastSentHeading, heading)) > 0.5f) {
            lastSentHeading = heading
            webView.evaluateJavascript("window.onNativeHeading && window.onNativeHeading($heading);", null)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
