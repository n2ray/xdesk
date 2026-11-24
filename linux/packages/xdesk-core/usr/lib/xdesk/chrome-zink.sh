# Sourced only by the Chrome launcher, after the session's GPU configuration.
# The KGSL driver reports the silicon revision after the GPU model.
xdesk_chrome_zink() {
    [ "${XDESK_CHROME_ZINK:-auto}" != 0 ] &&
    [ "${TERMUX_VA_BRIDGE:-}" = 1 ] && [ -S /tmp/termux-va/termux-va.sock ] &&
    [ -r /usr/local/lib/xdesk-hwvideo/revision ] &&
    [ "$(cat /usr/local/lib/xdesk-hwvideo/revision)" = b67039566476 ] || return 0
    case "$(cat /sys/class/kgsl/kgsl-3d0/gpu_model 2>/dev/null)" in
        Adreno830|Adreno830v[0-9]*) ;;
        *) return 0 ;;
    esac
    local_dir=/usr/lib/xdesk/chrome-zink
    [ -r "$local_dir/libxdesk-dri3.so" ] && [ -r "$local_dir/gl/libGL.so.1" ] &&
        [ -r "$local_dir/va/termuxva_drv_video.so" ] &&
        [ -r /usr/lib/aarch64-linux-gnu/libGL.so.1 ] || { unset local_dir; return 0; }
    if [ -z "${VK_DRIVER_FILES+x}${VK_ICD_FILENAMES+x}${VK_ADD_DRIVER_FILES+x}" ] &&
        [ -r "$local_dir/vk/freedreno_icd.aarch64.json" ] && [ -r "$local_dir/vk/libvulkan_freedreno.so" ]; then
        # The private driver waits upstream only after a real X11 pixmap flip.
        export XDESK_WSI_WAIT_FLIP="${XDESK_WSI_WAIT_FLIP:-true}"
        export VK_DRIVER_FILES="$local_dir/vk/freedreno_icd.aarch64.json"
    fi
    export LD_PRELOAD="$local_dir/libxdesk-dri3.so${LD_PRELOAD:+:$LD_PRELOAD}"
    export LD_LIBRARY_PATH="$local_dir/gl${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    export LIBVA_DRIVERS_PATH="$local_dir/va"
    export MESA_LOADER_DRIVER_OVERRIDE=zink ZINK_DESCRIPTORS=lazy TU_DEBUG="${TU_DEBUG:-sysmem}"
    CHROME_FLAGS="--no-sandbox --ozone-platform=x11 --use-gl=angle --use-angle=gl --ignore-gpu-blocklist
        --disable-features=Vulkan,AcceleratedVideoDecodeLinuxZeroCopyGL,VaapiVideoMinResolutionForPerformance
        --enable-features=AcceleratedVideoDecoder,AcceleratedVideoDecodeLinuxGL,PlatformHEVCDecoderSupport
        --hardware-video-device-path=/dev/kgsl-3d0"
    unset local_dir
}
xdesk_chrome_zink
