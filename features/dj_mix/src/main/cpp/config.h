/**
 * Minimal aubio configuration for Android NDK builds.
 *
 * This file replaces the config.h that aubio's waf build system would normally
 * generate. It is found first in the include path because CMakeLists.txt puts
 * ${CMAKE_CURRENT_SOURCE_DIR} before ${AUBIO_SRC_DIR}.
 *
 * Key choices:
 *  - No FFTW3 / Accelerate / Intel IPP → fft.c falls back to bundled ooura FFT
 *  - No file I/O backends (no libsndfile, libavcodec, JACK)
 *  - Single-precision floats (smpl_t = float, NOT double)
 */
#pragma once

#define PACKAGE_VERSION  "0.4.9"
#define AUBIO_VERSION    "0.4.9"

/* Standard C headers available on every Android NDK target */
#define HAVE_STDLIB_H   1
#define HAVE_STDIO_H    1
#define HAVE_MATH_H     1
#define HAVE_STRING_H   1
#define HAVE_LIMITS_H   1
#define HAVE_STDARG_H   1
#define HAVE_COMPLEX_H  1   /* Android NDK provides <complex.h> */

/*
 * FFT backend — intentionally undefined so fft.c uses the bundled ooura FFT.
 * Do NOT define any of these unless you add the corresponding library.
 */
/* #undef HAVE_FFTW3       */
/* #undef HAVE_FFTW3F      */
/* #undef HAVE_ACCELERATE  */
/* #undef HAVE_INTEL_IPP   */

/*
 * Audio I/O backends — all undefined.
 * We pass raw PCM samples directly via JNI; no file reading is needed.
 */
/* #undef HAVE_SNDFILE     */
/* #undef HAVE_AVCODEC     */
/* #undef HAVE_JACK        */
/* #undef HAVE_WAVWRITE    */

/*
 * Precision — NOT defined → smpl_t = float (single precision).
 * Double precision is unnecessary for beat tracking and doubles RAM usage.
 */
/* #undef HAVE_AUBIO_DOUBLE */

/* Memory copy optimisations — off for maximum portability across ABIs */
#define HAVE_MEMCPY_HACKS 0