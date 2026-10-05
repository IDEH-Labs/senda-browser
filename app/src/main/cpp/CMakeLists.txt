cmake_minimum_required(VERSION 3.22.1)

project("senda-llama" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# Optimizado también en la variante debug: el CMAKE_C_FLAGS de caché quedaba oculto por la variable del
# toolchain del NDK y ggml se compilaba sin -O (una respuesta corta tardaba ~3 min en el teléfono)
add_compile_options(-O3)

if(DEFINED ANDROID_ABI)
    message(STATUS "[Senda Llama] Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI OFF)
        # Una variante de la CPU de ggml por nivel de ARM (8.0, 8.2+dotprod, 8.2+fp16, 8.6+i8mm, 9.x), elegida
        # al arrancar por ggml_backend_load_all_from_path. Antes todo iba compilado para ARMv8.0 sin dotprod:
        # en un Snapdragon 695 el prompt se leía a ~3-6 tokens/s y cada respuesta tardaba 45-70 s
        set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
        set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
        set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
        # GPU Adreno por OpenCL como módulo aparte (libggml-opencl.so). Versión 2.0: el driver del Adreno 619
        # (Snapdragon 695) es OpenCL 2.0 y con 3.0 el módulo pediría funciones que no tiene y no cargaría.
        # Sin OpenCL público o con GPU no Adreno, ggml no registra el dispositivo y todo sigue en la CPU.
        # Usarla o no lo decide la calibración medida en cada teléfono (SendaAiCalibrator)
        set(GGML_OPENCL ON CACHE BOOL "" FORCE)
        set(GGML_OPENCL_TARGET_VERSION "200" CACHE STRING "" FORCE)
        add_library(senda_opencl_link_stub SHARED ${CMAKE_CURRENT_LIST_DIR}/third_party/opencl/opencl_link_stub.c)
        set_target_properties(senda_opencl_link_stub PROPERTIES OUTPUT_NAME OpenCL)
        # FindOpenCL acepta estas variables tal cual: el módulo se enlaza contra el stub, que nunca va al APK
        set(OpenCL_INCLUDE_DIR ${CMAKE_CURRENT_LIST_DIR}/third_party/opencl CACHE PATH "" FORCE)
        set(OpenCL_LIBRARY senda_opencl_link_stub CACHE STRING "" FORCE)

        # Vulkan (GPU Mali, Xclipse, PowerVR) solo en compilaciones de prueba (-PsendaVulkan en Gradle): pesa
        # ~34 MB instalado y aún no se probó en esas GPU; un driver defectuoso podría cerrar la app al enumerar
        # dispositivos, antes de cualquier calibración. Necesita cabeceras Vulkan-Hpp, SPIRV-Headers y glslc
        option(SENDA_VULKAN "Módulo Vulkan de prueba" OFF)
        if(SENDA_VULKAN)
            set(GGML_VULKAN ON CACHE BOOL "" FORCE)
            set(Vulkan_INCLUDE_DIR ${SENDA_VULKAN_HEADERS} CACHE PATH "" FORCE)
            set(Vulkan_LIBRARY ${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/${ANDROID_PLATFORM_LEVEL}/libvulkan.so CACHE FILEPATH "" FORCE)
            set(Vulkan_GLSLC_EXECUTABLE ${SENDA_GLSLC} CACHE FILEPATH "" FORCE)
            set(SPIRV-Headers_DIR ${SENDA_SPIRV_PREFIX}/share/cmake/SPIRV-Headers CACHE PATH "" FORCE)
            # ggml-vulkan busca SPIRV-Headers pero no añade su carpeta de cabeceras
            include_directories(SYSTEM ${SENDA_SPIRV_PREFIX}/include)
        endif()
        # Sin OpenMP: ggml usa su propio grupo de hilos. libomp.so del NDK 26 viene alineada a 4 KB
        # (falla con páginas de 16 KB) y enlazada estática la carga del modelo se quedaba colgada
        set(GGML_OPENMP OFF)
    else()
        set(GGML_OPENMP OFF)
    endif()
endif()

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama: build tests" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama: build examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama: build server" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "llama: build common utils library" FORCE)
# Compartidas: las variantes de CPU se cargan como módulos y necesitan libggml-base compartida
set(BUILD_SHARED_LIBS ON CACHE BOOL "llama: build shared libs" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "llama: build tools" FORCE)

# Android 15+ con páginas de 16 KB: todas las .so (llama, ggml y cada variante de CPU) alineadas a 16 KB
add_link_options("-Wl,-z,max-page-size=16384")

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
# Sin EXCLUDE_FROM_ALL: las variantes de CPU no son dependencias de enlace (se abren con dlopen) y solo así
# se compilan y Gradle las empaqueta junto a libsenda-llama. Herramientas, pruebas y ejemplos siguen apagados
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(senda-llama SHARED
    senda_llama_jni.cpp
)

target_include_directories(senda-llama PRIVATE
    ${LLAMA_SRC}
    ${LLAMA_SRC}/common
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
    ${LLAMA_SRC}/ggml/src
)

target_link_libraries(senda-llama
    llama
    llama-common
    android
    log
)

