package ru.simlabs.stream.utils

class Quaternion(axis: FloatArray) {
    private var x: Float = 0f
    private var y: Float = 0f
    private var z: Float = 0f
    private var w: Float = 0f

    fun set(q: Quaternion) {
        this.x = q.x
        this.y = q.y
        this.z = q.z
        this.w = q.w
    }

    init {
        set(axis)
    }

    /**
     * @param axis rotation axis, unit vector.
     */
    fun set(axis: FloatArray) {
        x = axis[0]
        y = axis[1]
        z = axis[2]
        w = axis[3]
    }

    fun mulThis(q: Quaternion): Quaternion {
        val nw = w * q.w - x * q.x - y * q.y - z * q.z
        val nx = w * q.x + x * q.w + y * q.z - z * q.y
        val ny = w * q.y + y * q.w + z * q.x - x * q.z
        z = w * q.z + z * q.w + x * q.y - y * q.x
        w = nw
        x = nx
        y = ny
        return this
    }

    /**
     * Converts this Quaternion into a matrix, returning it as a float array.
     */
    fun toMatrix(): FloatArray {
        val matrixs = FloatArray(16)
        toMatrix(matrixs)
        return matrixs
    }

    /**
     * Converts this Quaternion into a matrix, placing the values into the given array.
     * @param matrixs 16-length float array.
     */
    fun toMatrix(matrixs: FloatArray) {
        matrixs[3] = 0.0f
        matrixs[7] = 0.0f
        matrixs[11] = 0.0f
        matrixs[12] = 0.0f
        matrixs[13] = 0.0f
        matrixs[14] = 0.0f
        matrixs[15] = 1.0f

        matrixs[0] = (1.0f - 2.0f * (y * y + z * z))
        matrixs[1] = (2.0f * (x * y - z * w))
        matrixs[2] = (2.0f * (x * z + y * w))

        matrixs[4] = (2.0f * (x * y + z * w))
        matrixs[5] = (1.0f - 2.0f * (x * x + z * z))
        matrixs[6] = (2.0f * (y * z - x * w))

        matrixs[8] = (2.0f * (x * z - y * w))
        matrixs[9] = (2.0f * (y * z + x * w))
        matrixs[10] = (1.0f - 2.0f * (x * x + y * y))
    }

}