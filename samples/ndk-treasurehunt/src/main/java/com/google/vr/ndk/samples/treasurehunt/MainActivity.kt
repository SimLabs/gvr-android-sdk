/* Copyright 2017 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.vr.ndk.samples.treasurehunt

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import com.google.vr.ndk.base.AndroidCompat
import com.google.vr.ndk.base.GvrLayout
import ru.simlabs.stream.StreamCommander
import ru.simlabs.stream.StreamDecoder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A Google VR NDK sample application.
 *
 *
 * This app presents a scene consisting of a planar ground grid and a floating "treasure" cube.
 * When the user finds the "treasure", they can invoke the trigger action, and the cube will be
 * randomly repositioned. When in Cardboard mode, the user must gaze at the cube and use the
 * Cardboard trigger button. When in Daydream mode, the user can use the controller to position the
 * cursor, and use the controller buttons to invoke the trigger action.
 *
 *
 * This is the main Activity for the sample application. It initializes a GLSurfaceView to allow
 * rendering, a GvrLayout for GVR API access, and forwards relevant events to the native renderer
 * where rendering and interaction are handled.
 */
@SuppressLint("ClickableViewAccessibility")
class MainActivity : Activity() {

    // Opaque native pointer to the native TreasureHuntRenderer instance.
    private var nativeTreasureHuntRenderer: Long = 0

    private var gvrLayout: GvrLayout? = null
    private lateinit var surfaceView: GLSurfaceView

    // Note that pause and resume signals to the native renderer are performed on the GL thread,
    // ensuring thread-safety.
    private val pauseNativeRunnable = Runnable { nativeOnPause(nativeTreasureHuntRenderer) }

    private val resumeNativeRunnable = Runnable { nativeOnResume(nativeTreasureHuntRenderer) }

    private lateinit var streamingSurfaceTexture: SurfaceTexture
    private lateinit var streamCommander: StreamCommander

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    179
            )
        }

        // Ensure fullscreen immersion.
        setImmersiveSticky()
        window
                .decorView
                .setOnSystemUiVisibilityChangeListener { visibility ->
                    if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                        setImmersiveSticky()
                    }
                }

        // Initialize GvrLayout and the native renderer.
        gvrLayout = GvrLayout(this)
        nativeTreasureHuntRenderer = nativeCreateRenderer(
                javaClass.classLoader,
                this.applicationContext,
                gvrLayout!!.gvrApi.nativeGvrContext)

        // Add the GLSurfaceView to the GvrLayout.
        surfaceView = GLSurfaceView(this)
        surfaceView.setEGLContextClientVersion(3)
        surfaceView.setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setRenderer(
                object : GLSurfaceView.Renderer {
                    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
                        nativeInitializeGl(nativeTreasureHuntRenderer)

                        val textureID = nativeGetStreamingTextureID(nativeTreasureHuntRenderer)
                        Log.i("Streaming", "got texture with id $textureID")

                        val textureWidth = nativeGetStreamingTextureWidth(nativeTreasureHuntRenderer)
                        val textureHeight = nativeGetStreamingTextureHeight(nativeTreasureHuntRenderer)

                        streamingSurfaceTexture = SurfaceTexture(textureID)
                        streamCommander = StreamCommander {
                            StreamDecoder(
                                    false,
                                    Surface(streamingSurfaceTexture),
                                    textureWidth,
                                    textureHeight
                            )
                        }

                        // Setup streaming
                        streamCommander.connect("$STREAMING_ADDRESS:9002") { success ->
                            if (success) {
                                Log.i("Streaming", "connected to $STREAMING_ADDRESS:9002")
                            }
                        }
                    }

                    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {}

                    override fun onDrawFrame(gl: GL10) {
                        streamingSurfaceTexture.updateTexImage()
                        nativeDrawFrame(nativeTreasureHuntRenderer)
                    }
                })

        surfaceView.setOnTouchListener(
                View.OnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN ->
                            surfaceView.queueEvent { nativeOnFlyStateChanged(nativeTreasureHuntRenderer, true) }
                        MotionEvent.ACTION_UP   ->
                            surfaceView.queueEvent { nativeOnFlyStateChanged(nativeTreasureHuntRenderer, false) }
                        else                    -> return@OnTouchListener false
                    }
                    return@OnTouchListener true
                })
        gvrLayout!!.setPresentationView(surfaceView)

        // Add the GvrLayout to the View hierarchy.
        setContentView(gvrLayout)

        // Enable async reprojection.
        if (gvrLayout!!.setAsyncReprojectionEnabled(true)) {
            // Async reprojection decouples the app framerate from the display framerate,
            // allowing immersive interaction even at the throttled clockrates set by
            // sustained performance mode.
            AndroidCompat.setSustainedPerformanceMode(this, true)
        }

        // Enable VR Mode.
        AndroidCompat.setVrModeEnabled(this, true)
    }

    override fun onPause() {
        surfaceView.queueEvent(pauseNativeRunnable)
        surfaceView.onPause()
        gvrLayout!!.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        gvrLayout!!.onResume()
        surfaceView.onResume()
        surfaceView.queueEvent(resumeNativeRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Destruction order is important; shutting down the GvrLayout will detach
        // the GLSurfaceView and stop the GL thread, allowing safe shutdown of
        // native resources from the UI thread.
        nativeDestroyRenderer(nativeTreasureHuntRenderer)
        nativeTreasureHuntRenderer = 0
        streamCommander.disconnect()
        gvrLayout!!.shutdown()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        gvrLayout!!.onBackPressed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setImmersiveSticky()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Avoid accidental volume key presses while the phone is in the VR headset.
        return event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || super.dispatchKeyEvent(event)
    }

    private fun setImmersiveSticky() {
        window
                .decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    private external fun nativeGetStreamingTextureID(nativeTreasureHuntRenderer: Long): Int
    private external fun nativeGetStreamingTextureWidth(nativeTreasureHuntRenderer: Long): Int
    private external fun nativeGetStreamingTextureHeight(nativeTreasureHuntRenderer: Long): Int

    private external fun nativeCreateRenderer(
            appClassLoader: ClassLoader, context: Context, nativeGvrContext: Long): Long

    private external fun nativeDestroyRenderer(nativeTreasureHuntRenderer: Long)
    private external fun nativeInitializeGl(nativeTreasureHuntRenderer: Long)
    private external fun nativeDrawFrame(nativeTreasureHuntRenderer: Long): Long
    private external fun nativeOnFlyStateChanged(nativeTreasureHuntRenderer: Long, flyForward: Boolean)
    private external fun nativeOnPause(nativeTreasureHuntRenderer: Long)
    private external fun nativeOnResume(nativeTreasureHuntRenderer: Long)

    companion object {
        init {
            System.loadLibrary("gvr")
            System.loadLibrary("gvr_audio")
            System.loadLibrary("treasurehunt_jni")
        }

        private const val STREAMING_ADDRESS = "ws://192.168.1.112"
    }
}
