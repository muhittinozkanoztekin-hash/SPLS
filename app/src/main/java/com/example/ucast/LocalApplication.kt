package com.example.ucast

/**
 * Do not rename or move this class, and do not rename its package.
 *
 * libnative_lib.so (bundled under jniLibs/) was extracted from the original
 * "Ucast" APK. Its JNI_OnLoad registers native methods against the exact
 * class path "com/example/ucast/LocalApplication" with method names
 * "encodeString" / "decodeString". Android's JNI binds by (class path +
 * method name + signature), so this class has to keep this identity for
 * System.loadLibrary + the native calls below to resolve at all.
 *
 * We never learned what encode/decode actually do internally (custom
 * cipher, compiled into the .so) - we just reuse the compiled library
 * instead of reimplementing the algorithm.
 */
object LocalApplication {
    init {
        System.loadLibrary("native_lib")
    }

    @JvmStatic
    external fun encodeString(input: String): String

    @JvmStatic
    external fun decodeString(input: String): String
}
