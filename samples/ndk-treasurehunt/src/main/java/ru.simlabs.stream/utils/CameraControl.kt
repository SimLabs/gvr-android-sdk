package ru.simlabs.stream.utils

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.util.Log
import com.koushikdutta.async.http.AsyncHttpClient
import com.koushikdutta.async.http.WebSocket
import ru.simlabs.stream.toInt

enum class InputCommands {
    MOUSE_MOVE,
    MOUSE_DOWN,
    MOUSE_UP,
    MOUSE_WHEEL,
    MOUSE_ENTER,
    MOUSE_LEAVE,
    KEY_DOWN,
    KEY_UP,

    CHANGE_ORIENTATION,
    CAMERA_NEXT,
    CAMERA_NAME,
    CAMERA_VIEW,
}

class CameraControl(context: Context) : GLSurfaceView(context), SensorEventListener {
    init {
        initSensors(context)
    }

    private var mSensorManager: SensorManager? = null
    private var mRotation: Sensor? = null
//    private var mGravity: Sensor? = null
//    private var mMagnetic: Sensor? = null
    private var webSocket: WebSocket? = null

    private val orientation = FloatArray(3)
    private val orienMatrix = FloatArray(16)

    var onNewCamera: ((String)->Unit)? = null

    private fun sendChangeOrientation(quat: FloatArray){
        send("${InputCommands.CHANGE_ORIENTATION.ordinal} ${quat.joinToString(" ")}")
    }

    fun connect(address: String, onConnectionResult: (Boolean) -> Unit) {
        if (this.webSocket != null) return

        AsyncHttpClient.getDefaultInstance().websocket(address, null) { exception, webSocket ->
            if (exception != null) {
                Log.e("Input socket", exception.toString())
                onConnectionResult(false)
                return@websocket
            }

            this.webSocket = webSocket

            onConnectionResult(true)

            println("before webSocket.setStringCallback")

            webSocket.setStringCallback { msg ->
                val list = msg.split(" ")
                println("message received: $msg")
                if (!list.isEmpty()) {
                    val head = InputCommands.values()[Integer.parseInt(list[0])]
                    val args = list.subList(1, list.size)
                    println("onReceive invoked: " + head + " " + args[0])
                    onReceive(head, args)
                }

            }
        }
    }

    fun disconnect(){
        webSocket?.close()
        webSocket = null
    }

    fun switchToNextCamera(next: Boolean){
        send("${InputCommands.CAMERA_NEXT.ordinal} ${next.toInt()}")
    }

    fun toggleCameraView() {
        send("${InputCommands.CAMERA_VIEW.ordinal}")
    }

    private fun onReceive(head: InputCommands, args: List<String>){
        when(head) {
            InputCommands.CAMERA_NAME -> {
                println("inside onReceve")
                onNewCamera?.invoke(args[0])
            }
            else -> println("From server to camera control: ${head.name} $args")
        }
    }

    private fun send(msg: String) {
        webSocket?.send(msg)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val quat: FloatArray = floatArrayOf(event.values[0], event.values[1], event.values[2], event.values[3])
                val q = Quaternion(quat)
                // flip coordinates to account for device orientation
                // this is analogous to SensorManager.remapCoordinateSystem(inOrientMatrix, SensorManager.AXIS_Z, SensorManager.AXIS_X, outOrientMatrix);
                // it is a 120 degrees rotation about (1, 1, 1) axis
                q.mulThis(Quaternion(floatArrayOf(.5f, .5f, .5f, .5f)))
                q.toMatrix(orienMatrix)
                SensorManager.getOrientation(orienMatrix, orientation)
                val rad2deg = 180 / Math.PI.toFloat()
                val c = orientation[0] * rad2deg //azimuth
                val p = orientation[1] * rad2deg //pitch
                val r = orientation[2] * rad2deg //roll
                sendChangeOrientation(floatArrayOf(c, p, -r))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    fun subscribeSensors() {
        mSensorManager!!.registerListener(this, mRotation, SensorManager.SENSOR_DELAY_GAME)
//        mSensorManager!!.registerListener(this, mGravity, SensorManager.SENSOR_DELAY_GAME)
//        mSensorManager!!.registerListener(this, mMagnetic, SensorManager.SENSOR_DELAY_GAME)
    }

    fun unsubscriveSensors() {
        mSensorManager!!.unregisterListener(this)
    }

    private fun initSensors(context: Context) {
        mSensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager
        mRotation = mSensorManager!!.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
//        mGravity = mSensorManager!!.getDefaultSensor(Sensor.TYPE_GRAVITY)
//        mMagnetic = mSensorManager!!.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    }

//    override fun onTouchEvent(e: MotionEvent): Boolean {
//        // MotionEvent reports input details from the touch screen
//        // and other input controls. In this case, you are only
//        // interested in events where the touch position changed.
//
//        val x = e.x
//        val y = e.y
//
//        when (e.action) {
//            MotionEvent.ACTION_MOVE -> {
//
//                val dx = x - mPreviousX
//                //subtract, so the cube moves the same direction as your finger.
//                //with plus it moves the opposite direction.
//                //                myRender.setX(myRender.getX() - (dx * TOUCH_SCALE_FACTOR));
//
//                val dy = y - mPreviousY
//                //                myRender.setY(myRender.getY() - (dy * TOUCH_SCALE_FACTOR));
//                myRender.rotate(dx * TOUCH_SCALE_FACTOR, dy * TOUCH_SCALE_FACTOR)
//            }
//        }
//
//        mPreviousX = x
//        mPreviousY = y
//        return true
//    }

    companion object {
        //private final float TOUCH_SCALE_FACTOR = 180.0f / 320;
        private const val TOUCH_SCALE_FACTOR = 0.015f
    }


}
