set_project("jingwei_rime_jni")
set_version("0.12.0")
set_languages("cxx17")

add_rules("mode.release")
add_requires("librime 1.17.0", {configs = {shared = false}})

target("rime_jni")
    set_kind("shared")
    add_files("../app/src/main/cpp/rime_jni.cpp")
    add_packages("librime")
    add_includedirs("$(env ANDROID_NDK_HOME)/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include")
    add_syslinks("log", "android")
    set_targetdir("../app/src/main/jniLibs/$(arch)")
