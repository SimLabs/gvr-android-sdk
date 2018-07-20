# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

cmake_minimum_required(VERSION 3.4.1)

# Configure the sample code.
file(GLOB native_srcs "src/main/jni/*.cc")
add_library(treasurehunt_jni
    SHARED
    ${native_srcs})

target_include_directories(treasurehunt_jni
    PRIVATE src/main/jni
    )

# Include the GVR headers & libraries.
include_directories(
    ${GVR_INCLUDE}
    )

add_library(gvr-lib SHARED IMPORTED)
set_target_properties(
    gvr-lib
    PROPERTIES IMPORTED_LOCATION ${GVR_LIBPATH}/${ANDROID_ABI}/libgvr.so)

add_library(gvraudio-lib SHARED IMPORTED)
set_target_properties(
    gvraudio-lib
    PROPERTIES IMPORTED_LOCATION ${GVR_LIBPATH}/${ANDROID_ABI}/libgvr_audio.so)

# Include general Android libraries.
find_library(android-lib android)
find_library(EGL-lib EGL)
find_library(GLESv3-lib GLESv32)
message("GLES LIB " ${GLESv3-lib})
find_library(log-lib log)

set(SIMLABS_REPO_ROOT C:/Users/dan/CLionProjects/simlabs-repo)

set(LOGGER_EXTERNAL_DEPENDENCIES ${log-lib})
set(WOMBAT_EXTERNAL_DEPENDENCIES ${GLESv3-lib} ${EGL-lib})
add_subdirectory(${SIMLABS_REPO_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/wombat-build)

# Build final libtreasurehunt_jni.so

target_include_directories(treasurehunt_jni
        PRIVATE ${SIMLABS_REPO_ROOT}/src/_Include
        PRIVATE ${SIMLABS_REPO_ROOT}/src/aerodrome/_aerodrome_include
        )
add_dependencies(treasurehunt_jni wombat_android_test)
target_link_libraries(treasurehunt_jni
    gvr-lib
    gvraudio-lib

    wombat_android_test

    ${android-lib}
    ${EGL-lib}
    ${GLESv3-lib}
    ${log-lib}
    )
