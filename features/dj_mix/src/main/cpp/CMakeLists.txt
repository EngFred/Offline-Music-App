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