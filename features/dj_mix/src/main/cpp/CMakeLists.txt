cmake_minimum_required(VERSION 3.22.1)
project("bpm_analyzer" C CXX)

# ── aubio (BPM analysis) ──────────────────────────────────────────────────────
set(AUBIO_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/aubio/src)

add_library(aubio_static STATIC
    ${AUBIO_SRC_DIR}/fvec.c          ${AUBIO_SRC_DIR}/cvec.c
    ${AUBIO_SRC_DIR}/lvec.c          ${AUBIO_SRC_DIR}/fmat.c
    ${AUBIO_SRC_DIR}/vecutils.c      ${AUBIO_SRC_DIR}/mathutils.c
    ${AUBIO_SRC_DIR}/musicutils.c
    ${AUBIO_SRC_DIR}/utils/log.c     ${AUBIO_SRC_DIR}/utils/hist.c
    ${AUBIO_SRC_DIR}/utils/parameter.c ${AUBIO_SRC_DIR}/utils/scale.c
    ${AUBIO_SRC_DIR}/temporal/filter.c ${AUBIO_SRC_DIR}/temporal/biquad.c
    ${AUBIO_SRC_DIR}/spectral/fft.c  ${AUBIO_SRC_DIR}/spectral/ooura_fft8g.c
    ${AUBIO_SRC_DIR}/spectral/phasevoc.c ${AUBIO_SRC_DIR}/spectral/specdesc.c
    ${AUBIO_SRC_DIR}/spectral/statistics.c
    ${AUBIO_SRC_DIR}/onset/onset.c   ${AUBIO_SRC_DIR}/onset/peakpicker.c
    ${AUBIO_SRC_DIR}/tempo/tempo.c   ${AUBIO_SRC_DIR}/tempo/beattracking.c
)
target_include_directories(aubio_static PUBLIC ${CMAKE_CURRENT_SOURCE_DIR} ${AUBIO_SRC_DIR})
target_compile_definitions(aubio_static PRIVATE HAVE_CONFIG_H=1 AUBIO_UNSTABLE=1)
target_compile_options(aubio_static PRIVATE -w)

add_library(bpm_analyzer SHARED aubio_bridge.c)
target_include_directories(bpm_analyzer PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${AUBIO_SRC_DIR})
target_compile_definitions(bpm_analyzer PRIVATE HAVE_CONFIG_H=1 AUBIO_UNSTABLE=1)
target_link_libraries(bpm_analyzer aubio_static android log m)

# ── RubberBand (time-stretching for DJ tempo sync) ────────────────────────────
# We use FetchContent_Populate (not MakeAvailable) to download the source
# without executing RubberBand's own CMakeLists.txt, then compile via the
# single-compilation-unit file provided by the library for exactly this purpose.
include(FetchContent)

FetchContent_Declare(
    rubberband
    GIT_REPOSITORY https://github.com/breakfastquay/rubberband.git
    GIT_TAG        v3.3.0
    GIT_SHALLOW    TRUE
)

FetchContent_GetProperties(rubberband)
if (NOT rubberband_POPULATED)
    FetchContent_Populate(rubberband)
endif ()

add_library(rubber_stretcher SHARED
    rubberband_processor.cpp
    ${rubberband_SOURCE_DIR}/single/RubberBandSingle.cpp
)

target_include_directories(rubber_stretcher PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${rubberband_SOURCE_DIR}
    ${rubberband_SOURCE_DIR}/rubberband  # public header: RubberBandStretcher.h
)

# USE_BQRESAMPLER  → built-in BQ resampler; no libsamplerate dependency.
# No FFT define    → RubberBand falls back to its built-in FFT implementation.
# HAVE_POSIX_MEMALIGN → available in all Android NDK targets.
target_compile_definitions(rubber_stretcher PRIVATE
    NDEBUG=1
    USE_BQRESAMPLER=1
    HAVE_POSIX_MEMALIGN=1
    _USE_MATH_DEFINES=1
)

target_compile_options(rubber_stretcher PRIVATE -std=c++17 -fexceptions -frtti -w)
target_link_libraries(rubber_stretcher android log)