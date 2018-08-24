package ru.simlabs.stream.unreal

import android.opengl.*
import android.util.Log

class BitmapRenderer(private val mVulkanRenderer: Boolean) : android.graphics.SurfaceTexture.OnFrameAvailableListener {
    private var mFrameData: java.nio.Buffer? = null
    //    private int mLastFramePosition = -1;
    var surfaceTexture: android.graphics.SurfaceTexture? = null
        private set
    private var mTextureWidth = -1
    private var mTextureHeight = -1
    var surface: android.view.Surface? = null
        private set
    private var mFrameAvailable = false
    private var mTextureID = -1
    private var mFBO = -1
    private var mBlitVertexShaderID = -1
    private var mBlitFragmentShaderID = -1
    private val mTransformMatrix = FloatArray(16)
    private var mTriangleVerticesDirty = true
    private var mTextureSizeChanged = true
    private var mUseOwnContext: Boolean = true
    private var mSwizzlePixels = false

    private val GL_TEXTURE_EXTERNAL_OES = 0x8D65

    private var mEglDisplay: EGLDisplay? = null
    private var mEglContext: EGLContext? = null
    private var mEglSurface: EGLSurface? = null

    private var mSavedDisplay: EGLDisplay? = null
    private var mSavedContext: EGLContext? = null
    private var mSavedSurfaceDraw: EGLSurface? = null
    private var mSavedSurfaceRead: EGLSurface? = null

    private var mCreatedEGLDisplay = false

    val isValid: Boolean
        get() = surfaceTexture != null
    private val mTriangleVerticesData = floatArrayOf(
            // X, Y, U, V
            -1.0f, -1.0f, 0f, 0f, 1.0f, -1.0f, 1f, 0f, -1.0f, 1.0f, 0f, 1f, 1.0f, 1.0f, 1f, 1f)

    private var mTriangleVertices: java.nio.FloatBuffer? = null

    private val mBlitVextexShader = "attribute vec2 Position;\n" +
            "attribute vec2 TexCoords;\n" +
            "varying vec2 TexCoord;\n" +
            "void main() {\n" +
            "	TexCoord = TexCoords;\n" +
            "	gl_Position = vec4(Position, 0.0, 1.0);\n" +
            "}\n"

    // NOTE: We read the fragment as BGRA so that in the end, when
    // we glReadPixels out of the FBO, we get them in that order
    // and avoid having to swizzle the pixels in the CPU.
    private val mBlitFragmentShaderBGRA = "#extension GL_OES_EGL_image_external : require\n" +
            "uniform samplerExternalOES VideoTexture;\n" +
            "varying highp vec2 TexCoord;\n" +
            "void main()\n" +
            "{\n" +
            "	gl_FragColor = texture2D(VideoTexture, TexCoord).bgra;\n" +
            "}\n"
    private val mBlitFragmentShaderRGBA = "#extension GL_OES_EGL_image_external : require\n" +
            "uniform samplerExternalOES VideoTexture;\n" +
            "varying highp vec2 TexCoord;\n" +
            "void main()\n" +
            "{\n" +
            "	gl_FragColor = texture2D(VideoTexture, TexCoord).rgba;\n" +
            "}\n"

    private var mProgram: Int = 0
    private var mPositionAttrib: Int = 0
    private var mTexCoordsAttrib: Int = 0
    private var mBlitBuffer: Int = 0
    private var mTextureUniform: Int = 0

    init {
        mUseOwnContext = true

        mEglSurface = EGL14.EGL_NO_SURFACE
        mEglContext = EGL14.EGL_NO_CONTEXT
        mEglDisplay = EGL14.EGL_NO_DISPLAY

        if (mVulkanRenderer) {
            mSwizzlePixels = true
        } else {
            val rendererString = GLES20.glGetString(GLES20.GL_RENDERER)

            // Do not use shared context if Adreno before 400 or on older Android than Marshmallow
            if (rendererString.contains("Adreno (TM) ")) {
                val adrenoVersion = Integer.parseInt(rendererString.substring(12))
                if (adrenoVersion < 400 || android.os.Build.VERSION.SDK_INT < 22) {
                    Log.d(LOG_TAG, "disabled shared GL context on $rendererString")
                    mUseOwnContext = false
                }
            }
        }

        if (mUseOwnContext) {
            initContext()
            saveContext()
            makeCurrent()
            initSurfaceTexture()
            restoreContext()
        } else {
            initSurfaceTexture()
        }
    }

    private fun initContext() {
        mEglDisplay = EGL14.EGL_NO_DISPLAY
        var shareContext = EGL14.EGL_NO_CONTEXT

        val majorver = intArrayOf(0)
        val minorver = intArrayOf(0)
        if (!mVulkanRenderer) {
            mEglDisplay = EGL14.eglGetCurrentDisplay()
            shareContext = EGL14.eglGetCurrentContext()

            if (android.os.Build.VERSION.SDK_INT >= 18 &&
                    EGL14.eglQueryContext(mEglDisplay, shareContext, EGLExt.EGL_CONTEXT_MAJOR_VERSION_KHR, majorver, 0) &&
                    EGL14.eglQueryContext(mEglDisplay, shareContext, EGLExt.EGL_CONTEXT_MINOR_VERSION_KHR, minorver, 0)) {
                Log.d(LOG_TAG, "Existing GL context is version " + majorver[0] + "." + minorver[0])
            } else
            // on some devices eg Galaxy S6, the above fails but we do get EGL14.EGL_CONTEXT_CLIENT_VERSION=3
                if (EGL14.eglQueryContext(mEglDisplay, shareContext, EGL14.EGL_CONTEXT_CLIENT_VERSION, majorver, 0)) {
                    Log.d(LOG_TAG, "Existing GL context is version " + majorver[0])
                } else {
                    Log.d(LOG_TAG, "Existing GL context version not detected")
                }
        } else {
            mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (mEglDisplay === EGL14.EGL_NO_DISPLAY) {
                Log.e(LOG_TAG, "unable to get EGL14 display")
                return
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
                mEglDisplay = null
                Log.e(LOG_TAG, "unable to initialize EGL14 display")
                return
            }

            mCreatedEGLDisplay = true
        }

        val configSpec = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val num_config = IntArray(1)
        EGL14.eglChooseConfig(mEglDisplay, configSpec, 0, configs, 0, 1, num_config, 0)
        val contextAttribsES2 = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val contextAttribsES31 = intArrayOf(EGLExt.EGL_CONTEXT_MAJOR_VERSION_KHR, 3, EGLExt.EGL_CONTEXT_MINOR_VERSION_KHR, 1, EGL14.EGL_NONE)
        mEglContext = EGL14.eglCreateContext(mEglDisplay, configs[0], shareContext, if (majorver[0] == 3) contextAttribsES31 else contextAttribsES2, 0)

        if (EGL14.eglQueryString(mEglDisplay, EGL14.EGL_EXTENSIONS).contains("EGL_KHR_surfaceless_context")) {
            mEglSurface = EGL14.EGL_NO_SURFACE
        } else {
            val pbufferAttribs = intArrayOf(EGL14.EGL_NONE)
            mEglSurface = EGL14.eglCreatePbufferSurface(mEglDisplay, configs[0], pbufferAttribs, 0)
        }
    }

    private fun saveContext() {
        mSavedDisplay = EGL14.eglGetCurrentDisplay()
        mSavedContext = EGL14.eglGetCurrentContext()
        mSavedSurfaceDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        mSavedSurfaceRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
    }

    private fun makeCurrent() {
        EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)
    }

    private fun restoreContext() {
        EGL14.eglMakeCurrent(mSavedDisplay, mSavedSurfaceDraw, mSavedSurfaceRead, mSavedContext)
    }

    private fun initSurfaceTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        mTextureID = textures[0]
        if (mTextureID <= 0) {
            Log.e(LOG_TAG, "mTextureID <= 0")
            release()
            return
        }
        surfaceTexture = android.graphics.SurfaceTexture(mTextureID)
        surfaceTexture!!.setOnFrameAvailableListener(this)
        surface = android.view.Surface(surfaceTexture)

        val glInt = IntArray(1)

        GLES20.glGenFramebuffers(1, glInt, 0)
        mFBO = glInt[0]
        if (mFBO <= 0) {
            Log.e(LOG_TAG, "mFBO <= 0")
            release()
            return
        }

        // Special shaders for blit of movie texture.
        mBlitVertexShaderID = createShader(GLES20.GL_VERTEX_SHADER, mBlitVextexShader)
        if (mBlitVertexShaderID == 0) {
            Log.e(LOG_TAG, "mBlitVertexShaderID == 0")
            release()
            return
        }
        val mBlitFragmentShaderID = createShader(GLES20.GL_FRAGMENT_SHADER,
                if (mSwizzlePixels) mBlitFragmentShaderBGRA else mBlitFragmentShaderRGBA)
        if (mBlitFragmentShaderID == 0) {
            Log.e(LOG_TAG, "mBlitFragmentShaderID == 0")
            release()
            return
        }
        mProgram = GLES20.glCreateProgram()
        if (mProgram <= 0) {
            Log.e(LOG_TAG, "mProgram <= 0")
            release()
            return
        }
        GLES20.glAttachShader(mProgram, mBlitVertexShaderID)
        GLES20.glAttachShader(mProgram, mBlitFragmentShaderID)
        GLES20.glLinkProgram(mProgram)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(LOG_TAG, "Could not link program: ")
            Log.e(LOG_TAG, GLES20.glGetProgramInfoLog(mProgram))
            GLES20.glDeleteProgram(mProgram)
            mProgram = 0
            release()
            return
        }
        mPositionAttrib = GLES20.glGetAttribLocation(mProgram, "Position")
        mTexCoordsAttrib = GLES20.glGetAttribLocation(mProgram, "TexCoords")
        mTextureUniform = GLES20.glGetUniformLocation(mProgram, "VideoTexture")

        GLES20.glGenBuffers(1, glInt, 0)
        mBlitBuffer = glInt[0]
        if (mBlitBuffer <= 0) {
            Log.e(LOG_TAG, "mBlitBuffer <= 0")
            release()
            return
        }

        // Create blit mesh.
        mTriangleVertices = java.nio.ByteBuffer.allocateDirect(
                mTriangleVerticesData.size * FLOAT_SIZE_BYTES)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        mTriangleVerticesDirty = true

        // Set up GL state
        if (mUseOwnContext) {
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glDisable(GLES20.GL_STENCIL_TEST)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_DITHER)
            GLES20.glColorMask(true, true, true, true)
        }
    }

    private fun UpdateVertexData() {
        if (!mTriangleVerticesDirty || mBlitBuffer <= 0) {
            return
        }

        // fill it in
        mTriangleVertices!!.position(0)
        mTriangleVertices!!.put(mTriangleVerticesData).position(0)

        // save VBO state
        val glInt = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_ARRAY_BUFFER_BINDING, glInt, 0)
        val previousVBO = glInt[0]

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mBlitBuffer)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
                mTriangleVerticesData.size * FLOAT_SIZE_BYTES,
                mTriangleVertices, GLES20.GL_STATIC_DRAW)

        // restore VBO state
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, previousVBO)

        mTriangleVerticesDirty = false
    }

    private fun createShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        if (shader != 0) {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e(LOG_TAG, "Could not compile shader $shaderType:")
                Log.e(LOG_TAG, GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
                shader = 0
            }
        }
        return shader
    }

    override fun onFrameAvailable(st: android.graphics.SurfaceTexture) {
        synchronized(this) {
            Log.i("Streaming", "new frame available")
            mFrameAvailable = true
        }
    }

    // NOTE: Synchronized with updateFrameData to prevent frame
    // updates while the surface may need to get reallocated.
    fun setSize(width: Int, height: Int) {
        synchronized(this) {
            if (width != mTextureWidth || height != mTextureHeight) {
                mTextureWidth = width
                mTextureHeight = height
                mFrameData = null
                mTextureSizeChanged = true
            }
        }
    }

    fun resolutionChanged(): Boolean {
        var changed = false

        synchronized(this) {
            changed = mTextureSizeChanged
            mTextureSizeChanged = false
        }

        return changed
    }

    fun updateFrameData(): java.nio.Buffer? {
        synchronized(this) {
            if (null == mFrameData && mTextureWidth > 0 && mTextureHeight > 0) {
                mFrameData = java.nio.ByteBuffer.allocateDirect(mTextureWidth * mTextureHeight * 4)
            }
            // Copy surface texture to frame data.
            if (!copyFrameTexture(0, mFrameData)) {
                return null
            }
        }
        return mFrameData
    }

    fun updateFrameData(destTexture: Int): Boolean {
        synchronized(this) {
            // Copy surface texture to destination texture.
            if (!copyFrameTexture(destTexture, null)) {
                return false
            }
        }
        return true
    }

    // Copy the surface texture to another texture, or to raw data.
    // Note: copying to raw data creates a temporary FBO texture.
    private fun copyFrameTexture(destTexture: Int, destData: java.nio.Buffer?): Boolean {
        if (!mFrameAvailable) {
            // We only return fresh data when we generate it. At other
            // time we return nothing to indicate that there was nothing
            // new to return. The media player deals with this by keeping
            // the last frame around and using that for rendering.
            return false
        }
        mFrameAvailable = false
        // commented unused
        //        int current_frame_position = getCurrentPosition();
        //        mLastFramePosition = current_frame_position;
        if (null == surfaceTexture) {
            // Can't update if there's no surface to update into.
            return false
        }

        val glInt = IntArray(1)

        // Either use own context or save states
        var previousBlend = false
        var previousCullFace = false
        var previousScissorTest = false
        var previousStencilTest = false
        var previousDepthTest = false
        var previousDither = false
        var previousFBO = 0
        var previousVBO = 0
        val previousMinFilter: Int
        val previousMagFilter: Int
        val previousViewport = IntArray(4)
        if (mUseOwnContext) {
            // Received reports of these not being preserved when changing contexts
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glGetTexParameteriv(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, glInt, 0)
            previousMinFilter = glInt[0]
            GLES20.glGetTexParameteriv(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, glInt, 0)
            previousMagFilter = glInt[0]

            saveContext()
            makeCurrent()
        } else {
            // Clear gl errors as they can creep in from the UE4 renderer.
            GLES20.glGetError()

            previousBlend = GLES20.glIsEnabled(GLES20.GL_BLEND)
            previousCullFace = GLES20.glIsEnabled(GLES20.GL_CULL_FACE)
            previousScissorTest = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST)
            previousStencilTest = GLES20.glIsEnabled(GLES20.GL_STENCIL_TEST)
            previousDepthTest = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST)
            previousDither = GLES20.glIsEnabled(GLES20.GL_DITHER)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, glInt, 0)
            previousFBO = glInt[0]
            GLES20.glGetIntegerv(GLES20.GL_ARRAY_BUFFER_BINDING, glInt, 0)
            previousVBO = glInt[0]
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, previousViewport, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glGetTexParameteriv(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, glInt, 0)
            previousMinFilter = glInt[0]
            GLES20.glGetTexParameteriv(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, glInt, 0)
            previousMagFilter = glInt[0]

            glVerify("save state")
        }

        // Get the latest video texture frame.
        surfaceTexture!!.updateTexImage()

        surfaceTexture!!.getTransformMatrix(mTransformMatrix)

        val UMin = mTransformMatrix[12]
        val UMax = UMin + mTransformMatrix[0]
        val VMin = mTransformMatrix[13]
        val VMax = VMin + mTransformMatrix[5]

        if (mTriangleVerticesData[2] != UMin ||
                mTriangleVerticesData[6] != UMax ||
                mTriangleVerticesData[11] != VMin ||
                mTriangleVerticesData[3] != VMax) {
            mTriangleVerticesData[10] = UMin
            mTriangleVerticesData[2] = mTriangleVerticesData[10]
            mTriangleVerticesData[14] = UMax
            mTriangleVerticesData[6] = mTriangleVerticesData[14]
            mTriangleVerticesData[15] = VMin
            mTriangleVerticesData[11] = mTriangleVerticesData[15]
            mTriangleVerticesData[7] = VMax
            mTriangleVerticesData[3] = mTriangleVerticesData[7]
            mTriangleVerticesDirty = true
        }

        destData?.position(0)

        if (!mUseOwnContext) {
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glDisable(GLES20.GL_STENCIL_TEST)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_DITHER)
            GLES20.glColorMask(true, true, true, true)

            glVerify("reset state")
        }

        GLES20.glViewport(0, 0, mTextureWidth, mTextureHeight)

        glVerify("set viewport")

        // Set-up FBO target texture..
        val FBOTextureID: Int
        if (null != destData) {
            // Create temporary FBO for data copy.
            GLES20.glGenTextures(1, glInt, 0)
            FBOTextureID = glInt[0]
        } else {
            // Use the given texture as the FBO.
            FBOTextureID = destTexture
        }
        // Set the FBO to draw into the texture one-to-one.
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, FBOTextureID)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // Create the temp FBO data if needed.
        if (null != destData) {
            //int w = 1<<(32-Integer.numberOfLeadingZeros(mTextureWidth-1));
            //int h = 1<<(32-Integer.numberOfLeadingZeros(mTextureHeight-1));
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0,
                    GLES20.GL_RGBA,
                    mTextureWidth, mTextureHeight,
                    0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        }

        glVerify("set-up FBO texture")

        // Set to render to the FBO.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFBO)

        GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, FBOTextureID, 0)

        // check status
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(LOG_TAG, "Failed to complete framebuffer attachment ($status)")
        }

        // The special shaders to render from the video texture.
        GLES20.glUseProgram(mProgram)

        // Set the mesh that renders the video texture.
        UpdateVertexData()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mBlitBuffer)
        GLES20.glEnableVertexAttribArray(mPositionAttrib)
        GLES20.glVertexAttribPointer(mPositionAttrib, 2, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, 0)
        GLES20.glEnableVertexAttribArray(mTexCoordsAttrib)
        GLES20.glVertexAttribPointer(mTexCoordsAttrib, 2, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES,
                TRIANGLE_VERTICES_DATA_UV_OFFSET * FLOAT_SIZE_BYTES)

        glVerify("setup movie texture read")

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // connect 'VideoTexture' to video source texture (mTextureID).
        // mTextureID is bound to GL_TEXTURE_EXTERNAL_OES in updateTexImage
        GLES20.glUniform1i(mTextureUniform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID)

        // Draw the video texture mesh.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glFlush()

        // Read the FBO texture pixels into raw data.
        if (null != destData) {
            GLES20.glReadPixels(
                    0, 0, mTextureWidth, mTextureHeight,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                    destData)
        }

        glVerify("draw & read movie texture")

        // Restore state and cleanup.
        if (mUseOwnContext) {
            GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, 0, 0)

            if (null != destData && FBOTextureID > 0) {
                glInt[0] = FBOTextureID
                GLES20.glDeleteTextures(1, glInt, 0)
            }

            restoreContext()

            // Restore previous texture filtering
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, previousMinFilter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, previousMagFilter)
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFBO)
            if (null != destData && FBOTextureID > 0) {
                glInt[0] = FBOTextureID
                GLES20.glDeleteTextures(1, glInt, 0)
            }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, previousVBO)

            GLES20.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
            if (previousBlend) GLES20.glEnable(GLES20.GL_BLEND)
            if (previousCullFace) GLES20.glEnable(GLES20.GL_CULL_FACE)
            if (previousScissorTest) GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            if (previousStencilTest) GLES20.glEnable(GLES20.GL_STENCIL_TEST)
            if (previousDepthTest) GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            if (previousDither) GLES20.glEnable(GLES20.GL_DITHER)

            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, previousMinFilter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, previousMagFilter)

            // invalidate cached state in RHI
            GLES20.glDisableVertexAttribArray(mPositionAttrib)
            GLES20.glDisableVertexAttribArray(mTexCoordsAttrib)
        }

        return true
    }

    private fun glVerify(op: String) {
        val error = GLES20.glGetError()

        if (error != GLES20.GL_NO_ERROR) {
            Log.e(LOG_TAG, "MediaPlayer\$BitmapRenderer: $op: glGetError $error")
            throw RuntimeException("$op: glGetError $error")
        }
    }


    private fun glWarn(op: String) {
        val error = GLES20.glGetError()

        if (error != GLES20.GL_NO_ERROR) {
            Log.w(LOG_TAG, "MediaPlayer\$BitmapRenderer: $op: glGetError $error")
        }
    }

    fun release() {
        if (null != surface) {
            surface!!.release()
            surface = null
        }
        if (null != surfaceTexture) {
            surfaceTexture!!.release()
            surfaceTexture = null
        }
        val glInt = IntArray(1)
        if (mBlitBuffer > 0) {
            glInt[0] = mBlitBuffer
            GLES20.glDeleteBuffers(1, glInt, 0)
            mBlitBuffer = -1
        }
        if (mProgram > 0) {
            GLES20.glDeleteProgram(mProgram)
            mProgram = -1
        }
        if (mBlitVertexShaderID > 0) {
            GLES20.glDeleteShader(mBlitVertexShaderID)
            mBlitVertexShaderID = -1
        }
        if (mBlitFragmentShaderID > 0) {
            GLES20.glDeleteShader(mBlitFragmentShaderID)
            mBlitFragmentShaderID = -1
        }
        if (mFBO > 0) {
            glInt[0] = mFBO
            GLES20.glDeleteFramebuffers(1, glInt, 0)
            mFBO = -1
        }
        if (mTextureID > 0) {
            glInt[0] = mTextureID
            GLES20.glDeleteTextures(1, glInt, 0)
            mTextureID = -1
        }
        if (mEglSurface !== EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(mEglDisplay, mEglSurface)
            mEglSurface = EGL14.EGL_NO_SURFACE
        }
        if (mEglContext !== EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(mEglDisplay, mEglContext)
            mEglContext = EGL14.EGL_NO_CONTEXT
        }
        if (mCreatedEGLDisplay) {
            EGL14.eglTerminate(mEglDisplay)
            mEglDisplay = EGL14.EGL_NO_DISPLAY
            mCreatedEGLDisplay = false
        }
    }

    companion object {

        private val LOG_TAG = "BitMapRenderer"

        private val FLOAT_SIZE_BYTES = 4
        private val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 4 * FLOAT_SIZE_BYTES
        private val TRIANGLE_VERTICES_DATA_UV_OFFSET = 2
    }
}