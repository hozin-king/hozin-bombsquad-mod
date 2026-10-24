cmake_minimum_required(VERSION 3.22)
project(bs_mod)

include(FetchContent)

# Dobby (hook library) diambil saat build via FetchContent — TIDAK di-vendor.
# Pakai URL arsip (bukan git clone) agar tidak gagal di runner CI.
# v1.0 belum memakai hook Dobby (slot pola masih kosong), tapi library
# tetap di-link agar riset lanjutan tinggal menambah hook tanpa mengubah
# build. Patch di bawah TERBUKTI menghijaukan CI di project HCR/UE-Hook.
FetchContent_Declare(
    dobby
    URL https://github.com/jmpews/Dobby/archive/5dfc8546954ce3b3198132ab13fddb89ee92cdd7.zip
)
FetchContent_GetProperties(dobby)
if(NOT dobby_POPULATED)
    FetchContent_Populate(dobby)

    # ---- Patch Dobby: upstream 5dfc854 "broken" untuk NDK modern ----

    # 1. closure_bridge_arm64.asm: sintaks Mach-O "@PAGE"/"@PAGEOFF" ditolak
    #    assembler LLVM pada target ELF. Ganti dengan ldr-literal dari
    #    entri .quad yang sudah ada di file yang sama.
    set(_f "${dobby_SOURCE_DIR}/source/TrampolineBridge/ClosureTrampolineBridge/arm64/closure_bridge_arm64.asm")
    file(READ "${_f}" _c)
    string(REPLACE
        "adrp TMP_REG_0, cdecl(common_closure_bridge_handler)@PAGE\nadd TMP_REG_0, TMP_REG_0, cdecl(common_closure_bridge_handler)@PAGEOFF\nblr TMP_REG_0"
        "ldr TMP_REG_0, common_closure_bridge_handler_addr\nblr TMP_REG_0"
        _c "${_c}")
    file(WRITE "${_f}" "${_c}")

    # 2. common/os_arch_features.h: putus circular include.
    set(_f "${dobby_SOURCE_DIR}/common/os_arch_features.h")
    file(READ "${_f}" _c)
    string(REPLACE
        "#include <sys/types.h>\n#include <stddef.h>\n#include \"pac_kit.h\"\n\n#include \"PlatformUnifiedInterface/platform.h\""
        "#include <sys/types.h>\n#include <stddef.h>\n#include <stdint.h>\n#include <unistd.h>\n#include <sys/mman.h>\n#include \"pac_kit.h\""
        _c "${_c}")
    string(REPLACE
        "  auto page = (void *)ALIGN_FLOOR(address, OSMemory::PageSize());\n  if (!OSMemory::SetPermission(page, OSMemory::PageSize(), kReadExecute)) {\n    return;\n  }"
        "  (void)size;\n  long page_size = sysconf(_SC_PAGESIZE);\n  if (page_size <= 0)\n    return;\n  void *page = (void *)((uintptr_t)address & ~((uintptr_t)page_size - 1));\n  mprotect(page, (size_t)page_size, PROT_READ | PROT_EXEC);"
        _c "${_c}")
    file(WRITE "${_f}" "${_c}")

    # 3. code-patch-tool-posix.cc: include core/arch/Cpu.h yang sudah dihapus
    #    upstream tapi tertinggal.
    set(_f "${dobby_SOURCE_DIR}/source/Backend/UserMode/ExecMemory/code-patch-tool-posix.cc")
    file(READ "${_f}" _c)
    string(REPLACE
        "#include \"core/arch/Cpu.h\""
        "// (patched: bs-mod) upstream menghapus core/arch/Cpu.h tapi include tertinggal"
        _c "${_c}")
    file(WRITE "${_f}" "${_c}")

    # 4. ProcessRuntime.cc: a.start adalah method, field load_address -> base.
    set(_f "${dobby_SOURCE_DIR}/source/Backend/UserMode/PlatformUtil/Linux/ProcessRuntime.cc")
    file(READ "${_f}" _c)
    string(REPLACE "return (a.start < b.start);" "return (a.start() < b.start());" _c "${_c}")
    string(REPLACE "module.load_address" "module.base" _c "${_c}")
    file(WRITE "${_f}" "${_c}")

    # 5. SymbolResolver plugin: ikut refactor "load_address" -> "base".
    set(_f "${dobby_SOURCE_DIR}/builtin-plugin/SymbolResolver/elf/dobby_symbol_resolver.cc")
    file(READ "${_f}" _c)
    string(REPLACE "load_address" "base" _c "${_c}")
    file(WRITE "${_f}" "${_c}")

    message(STATUS "[bs-mod] Patch Dobby selesai")

    add_subdirectory(${dobby_SOURCE_DIR} ${dobby_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()

# Payload: di-load via dlopen oleh injector (mode root).
# (v1.1: gate TIDAK di sini — pintu utama = Keamanan.java, backstop
#  inline di payload.cpp. Tidak ada gate.cpp terpisah.)
add_library(payload SHARED
    payload/payload.cpp
)
target_include_directories(payload PRIVATE ${dobby_SOURCE_DIR}/include)
target_link_libraries(payload dobby_static log)
# Static-link STL: libpayload.so TIDAK lagi DT_NEEDED libc++_shared.so.
# (Game BombSquad tidak membundel libc++_shared.so dan namespace linker
# game tidak bisa resolve SONAME dari /data/local/tmp.)
target_link_options(payload PRIVATE -static-libstdc++)

# Probe MINIMAL untuk isolasi masalah inject: constructor hanya tulis
# breadcrumb + status JSON, lalu thread poll. TANPA Dobby, TANPA scan,
# TANPA hook. Kalau probe jalan tapi payload utama tidak, penyebabnya di
# init payload utama; kalau probe juga tidak jalan, masalahnya di
# mekanisme dlopen/injector.
add_library(payload_probe SHARED
    payload/payload_probe.cpp
)
set_target_properties(payload_probe PROPERTIES OUTPUT_NAME "payload_probe")
target_link_libraries(payload_probe log)
target_link_options(payload_probe PRIVATE -static-libstdc++)

# Injector: PIE executable, dikemas sebagai libinjector.so lalu dieksekusi
# dari nativeLibraryDir (trik standar agar bisa di-exec dari APK).
add_executable(injector injector/injector.c)
set_target_properties(injector PROPERTIES OUTPUT_NAME "libinjector.so")
target_link_libraries(injector log dl)
