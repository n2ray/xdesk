# glamor (the X server composing on the GPU, on with TERMUX_X11_GLAMOR=1) and the real libepoxy it needs.
# From termux_on_dex by PietroCav501 (GPL-3.0, github.com/PietroCav501/termux_on_dex, x11/), as are
# lorie/tod-glamor.c and patches/xserver-glamor.patch; its comments are in Italian.
# Included by xserver.cmake before XSERVER_LIBS; adds xserver_glamor and epoxy to TOD_GLAMOR_LIBS.
#
# libepoxy's generated sources (gl/egl_generated_dispatch.c and their headers) are made here at configure
# time, with libepoxy's own gen_dispatch.py: the copy in libepoxy/src is changed by patches/libepoxy.patch
# for the GLX headers, so a copy of it has that patch taken out again.

if (NOT TOD_EPOXY_DIR)
    set(TOD_EPOXY_DIR "${CMAKE_CURRENT_BINARY_DIR}/epoxy-gen")
    file(MAKE_DIRECTORY "${TOD_EPOXY_DIR}/src" "${TOD_EPOXY_DIR}/inc/epoxy")
    set(_epoxy "${CMAKE_CURRENT_SOURCE_DIR}/libepoxy")
    set(_unpatch "${CMAKE_CURRENT_SOURCE_DIR}/patches/libepoxy.patch")
    execute_process(COMMAND bash -c "cp '${_epoxy}/src/gen_dispatch.py' '${TOD_EPOXY_DIR}/src/' && if patch -p1 -d '${TOD_EPOXY_DIR}' -R --dry-run -i '${_unpatch}' > /dev/null 2>&1; then patch -p1 -d '${TOD_EPOXY_DIR}' -R -i '${_unpatch}' > /dev/null; fi"
            RESULT_VARIABLE _r)
    if (NOT _r EQUAL 0)
        message(FATAL_ERROR "glamor: no original gen_dispatch.py")
    endif ()
    foreach (x gl egl)
        execute_process(COMMAND "${Python3_EXECUTABLE}" "${TOD_EPOXY_DIR}/src/gen_dispatch.py" --source --header
                "--srcdir=${TOD_EPOXY_DIR}" "--includedir=${TOD_EPOXY_DIR}/inc/epoxy" "${_epoxy}/registry/${x}.xml"
                RESULT_VARIABLE _r)
        if (NOT _r EQUAL 0)
            message(FATAL_ERROR "glamor: generating libepoxy's ${x} sources failed")
        endif ()
    endforeach ()
endif ()

file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/epoxy/config.h" CONTENT "
#pragma once
#define ENABLE_GLX 0
#define ENABLE_EGL 1
#define ENABLE_X11 0
#define HAVE_KHRPLATFORM_H 0
#define EPOXY_PUBLIC __attribute__((visibility(\"hidden\"))) extern
")

add_library(epoxy STATIC
        "libepoxy/src/dispatch_common.c"
        "libepoxy/src/dispatch_egl.c"
        "${TOD_EPOXY_DIR}/gl_generated_dispatch.c"
        "${TOD_EPOXY_DIR}/egl_generated_dispatch.c")
target_include_directories(epoxy PUBLIC "${TOD_EPOXY_DIR}/inc" "libepoxy/include")
target_include_directories(epoxy PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/epoxy" "libepoxy/src")
target_compile_options(epoxy PRIVATE "-fPIC" "-fvisibility=hidden" "-w")

set(GLAMOR_SOURCES
        glamor.c glamor_copy.c glamor_core.c glamor_dash.c glamor_font.c glamor_composite_glyphs.c
        glamor_image.c glamor_lines.c glamor_segs.c glamor_render.c glamor_gradient.c glamor_prepare.c
        glamor_program.c glamor_rects.c glamor_spans.c glamor_text.c glamor_transfer.c glamor_transform.c
        glamor_trapezoid.c glamor_triangles.c glamor_addtraps.c glamor_glyphblt.c glamor_points.c
        glamor_pixmap.c glamor_largepixmap.c glamor_picture.c glamor_vbo.c glamor_window.c glamor_fbo.c
        glamor_compositerects.c glamor_utils.c glamor_sync.c)
list(TRANSFORM GLAMOR_SOURCES PREPEND "xserver/glamor/")
list(APPEND GLAMOR_SOURCES "lorie/tod-glamor.c")
add_library(xserver_glamor STATIC ${GLAMOR_SOURCES})
# epoxy prima di ${inc}: glamor deve vedere epoxy/gl.h, non il GL/gl.h finto fatto per GLX
target_include_directories(xserver_glamor PRIVATE "${TOD_EPOXY_DIR}/inc" "libepoxy/include" ${inc} "xserver/glamor")
target_compile_options(xserver_glamor PRIVATE ${c_only_compile_options} ${compile_options} "-DGLAMOR")
target_link_libraries(xserver_glamor epoxy)

set(TOD_GLAMOR_LIBS xserver_glamor epoxy)
