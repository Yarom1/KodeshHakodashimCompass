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

    // מצב "אנכי" נקבע עם היסטרזיס (סף שונה לכניסה/יציאה) כדי למנוע ריצוד בגבול המעבר
    private var useVerticalFormula = false

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

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // שלב 1: תיקון לפי סיבוב המסך (פורטרט/לנדסקייפ)
        val rotation = windowManager.defaultDisplay.rotation
        val (axisX, axisY) = when (rotation) {
            Surface.ROTATION_90 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
            Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
            Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
            else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
        }
        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, screenAdjustedMatrix)
        SensorManager.getOrientation(screenAdjustedMatrix, orientationFlat)

        val pitchDeg = Math.toDegrees(orientationFlat[1].toDouble())

        // שלב 2: היסטרזיס - נכנסים לנוסחת "אנכי" ב-65°, יוצאים ממנה רק מתחת ל-50°
        useVerticalFormula = when {
            abs(pitchDeg) > 65 -> true
            abs(pitchDeg) < 50 -> false
            else -> useVerticalFormula
        }

        var heading: Float
        if (useVerticalFormula) {
            // ליד אנכי - עוקבים אחרי ציר ה-Z (גב המכשיר) שנשאר אופקי ויציב,
            // במקום ציר ה-Y שנכנס לנקודת יחיד (gimbal lock) קרוב ל-90° הטיה
            SensorManager.remapCoordinateSystem(
                screenAdjustedMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, verticalRemapMatrix
            )
            SensorManager.getOrientation(verticalRemapMatrix, orientationVertical)
            heading = Math.toDegrees(orientationVertical[0].toDouble()).toFloat()
            // מתקנים לכיוון שהגב (לא החזית) של המכשיר מצביע אליו + 180 מעלות בהתאמה
            heading = (heading + 180f) % 360f
        } else {
            heading = Math.toDegrees(orientationFlat[0].toDouble()).toFloat()
        }
        if (heading < 0) heading += 360f

        if (Math.abs(heading - lastSentHeading) > 0.5f) {
            lastSentHeading = heading
            webView.evaluateJavascript("window.onNativeHeading && window.onNativeHeading($heading);", null)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
