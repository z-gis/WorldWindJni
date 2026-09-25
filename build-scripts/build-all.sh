#!/bin/bash

# 任一命令失败即退出
set -e

clear

# BASE_PATH：本脚本所在目录（build-scripts）——所有派生路径的唯一锚点，目录可整体迁移
BASE_PATH=$(
	cd "$(dirname "$0")"
	pwd
)

# REPO_ROOT：仓库根（build-scripts 的上一级），供派生 jniLibs 落位路径
REPO_ROOT=$(cd "$BASE_PATH/.." && pwd)

# 一次构建的目标 ABI（目录名须与 ANDROID_ABI 完全一致：x86_64 是下划线）
ABIS=(x86_64 arm64-v8a)
export API=24

# 构建工作区根：放 ndk/、cmake/、export/、third-party/ 等子目录；默认即脚本目录，可环境变量覆盖
# WSL 且脚本位于 Windows 挂载盘（/mnt/<盘>，9p/drvfs）时：NDK/cmake 解压出的符号链接与可执行位
# 无法保留（clang 等变成坏链接），交叉编译必然失败。此时自动把工作区重定向到 WSL 原生目录（ext4），
# 产物 .a 仍按 REPO_ROOT 落回挂载盘上的仓库 jniLibs（不受影响）。显式设 MAIN_DIR 可覆盖此行为。
if [ -z "${MAIN_DIR:-}" ] && [[ "$BASE_PATH" == /mnt/?/* ]]; then
  MAIN_DIR="$HOME/android-jni-build"
  echo "[WSL] 脚本在 Windows 挂载盘（9p），构建工作区自动改用原生目录（ext4）：$MAIN_DIR"
fi
export MAIN_DIR=${MAIN_DIR:-$BASE_PATH}
# 第三方安装前缀根（各库 make install 的目标）
export EXPORT_DIR=$MAIN_DIR/export
# 第三方源码包下载/解压/构建的统一工作区（保持脚本根目录干净）
export THIRD_PARTY_DIR=${THIRD_PARTY_DIR:-$MAIN_DIR/third-party}
mkdir -p "$THIRD_PARTY_DIR"

# —— 复用本地已下载的包，避免重定位到 ext4 后重复下载（github 源码包易抽风，能复用就复用）——
# PKG_CACHE：额外本地包缓存目录（可空格分隔多个），默认含脚本目录下的 third-party；
# 其中的 *.tar.gz / *.zip 与 boost/ 头目录会被种进 $THIRD_PARTY_DIR（cp -n 不覆盖已有的）。
export PKG_CACHE=${PKG_CACHE:-$BASE_PATH/third-party}

# NDK / cmake 安装包
for sub in ndk cmake; do
  if [ -d "$BASE_PATH/$sub" ] && [ "$MAIN_DIR" != "$BASE_PATH" ]; then
    mkdir -p "$MAIN_DIR/$sub"
    cp -n "$BASE_PATH"/$sub/*.zip "$BASE_PATH"/$sub/*.tar.gz "$MAIN_DIR/$sub/" 2>/dev/null || true
  fi
done

# 第三方源码包 + boost 头：从各缓存目录种入 $THIRD_PARTY_DIR
if [ "$MAIN_DIR" != "$BASE_PATH" ]; then
  for cache in $PKG_CACHE; do
    [ -d "$cache" ] || continue
    cp -n "$cache"/*.tar.gz "$cache"/*.zip "$THIRD_PARTY_DIR/" 2>/dev/null || true
    if [ -d "$cache/boost" ] && [ ! -d "$THIRD_PARTY_DIR/boost" ]; then
      echo "从缓存复用 boost 头：$cache/boost"
      cp -a "$cache/boost" "$THIRD_PARTY_DIR/boost"
    fi
  done
fi

echo "--------NDK--------"
source "$BASE_PATH/ndk.sh"

echo "--------cmake--------"
source "$BASE_PATH/cmake.sh"

# 编译产物 .a 的直接落位目标：worldwindjni 模块 jniLibs（依赖 REPO_ROOT，可覆盖）
export JNI_LIBS_DIR=${JNI_LIBS_DIR:-$REPO_ROOT/worldwindjni/src/main/cpp/jniLibs}

# 本脚本链产出的 .a 清单（每 ABI 14 个）：前 13 个链式构建，libcurl.a 由链内 build-openssl + build-curl 产出
LIBS=(libgdal.a libproj.a libsqlite3.a libkmlbase.a libkmldom.a libkmlengine.a \
      libkmlxsd.a libkmlconvenience.a libkmlregionator.a liburiparser.a \
      libminizip.a libexpat.a libz.a libcurl.a)

# build_one_abi <ABI>：按依赖顺序构建全部第三方库 + GDAL，产物安装到 $PREFIX/lib
build_one_abi() {
  export ABI=$1
  export PREFIX=$EXPORT_DIR/third_party-$ABI

  # 子脚本（source 进本函数）以 $MAIN_DIR 作为源码工作区；用 local 把它临时指向 third-party
  # （bash 动态作用域，函数返回后自动恢复全局 MAIN_DIR），使所有 tarball 下载/解压/构建都落在
  # $THIRD_PARTY_DIR，脚本根目录保持干净。
  local MAIN_DIR="$THIRD_PARTY_DIR"

  # 目标三元组与编译器（sqlite3 的 autotools configure 用 HOST/CC/CXX/CFLAGS；CMake 类库走 android.toolchain）
  if [ "$ABI" = "x86_64" ]; then
    export HOST="x86_64-linux-android"
    export CC="$TOOLCHAIN/bin/x86_64-linux-android$API-clang"
    export CXX="$TOOLCHAIN/bin/x86_64-linux-android$API-clang++"
  elif [ "$ABI" = "arm64-v8a" ]; then
    export HOST="aarch64-linux-android"
    export CC="$TOOLCHAIN/bin/aarch64-linux-android$API-clang"
    export CXX="$TOOLCHAIN/bin/aarch64-linux-android$API-clang++"
  else
    echo "Unsupported ABI: $ABI"
    exit 1
  fi
  export CFLAGS="--sysroot=$TOOLCHAIN/sysroot -fPIC"
  export CXXFLAGS="--sysroot=$TOOLCHAIN/sysroot -fPIC"
  export LDFLAGS="-fPIC"

  echo "==================== BUILD ABI=$ABI ===================="

  cd "$MAIN_DIR"

  echo "--------expat--------"
  source "$BASE_PATH/build-expat.sh"

  echo "--------minizip--------"
  source "$BASE_PATH/build-minizip.sh"

  echo "--------uriparser--------"
  source "$BASE_PATH/build-uriparser.sh"

  # curl 依赖 zlib（minizip 已产出 libz.a + zlib.h），故置于 minizip/uriparser 之后。
  # openssl 先编并 stage 到 $PREFIX，curl 再链它并把 ssl/crypto 合并进 libcurl.a。
  echo "--------openssl--------"
  source "$BASE_PATH/build-openssl.sh"

  echo "--------curl--------"
  source "$BASE_PATH/build-curl.sh"

  echo "--------cp boost--------"
  if [ -d "$PREFIX/include/boost" ]; then
    echo "boost already exists, skip copy"
  else
    mkdir -p "$PREFIX/include/boost"
    cp -r "$MAIN_DIR/boost/"* "$PREFIX/include/boost/"
  fi

  echo "--------libkml--------"
  source "$BASE_PATH/build-libkml.sh"

  echo "--------sqlite3--------"
  source "$BASE_PATH/build-sqlite3.sh"

  echo "--------proj--------"
  source "$BASE_PATH/build-proj.sh"

  echo "--------Build GDAL--------"
  export GDAL_VERSION=3.7.0
  cd "$MAIN_DIR"
  if [ ! -d "gdal-${GDAL_VERSION}" ]; then
    tar xzf "gdal-${GDAL_VERSION}.tar.gz"
  fi
  cd "gdal-${GDAL_VERSION}"

  # 编译目录按 ABI 隔离，两遍构建互不覆盖配置缓存
  local BUILD_DIR="build_android_cmake_$ABI"
  if [ -d "$BUILD_DIR" ]; then
      rm -rf "$BUILD_DIR"
  fi
  mkdir "$BUILD_DIR"
  cd "$BUILD_DIR"

  PKG_CONFIG_LIBDIR=$PREFIX/lib/pkgconfig cmake .. \
   -DUSE_CCACHE=OFF \
   -DCMAKE_INSTALL_PREFIX=$PREFIX \
   -DCMAKE_SYSTEM_NAME=Android \
   -DCMAKE_ANDROID_NDK=$NDK_ROOT\
   -DCMAKE_ANDROID_ARCH_ABI=$ABI \
   -DCMAKE_SYSTEM_VERSION=$API \
   "-DCMAKE_PREFIX_PATH=$PREFIX;$TOOLCHAIN/sysroot/usr/" \
   -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=NEVER \
   -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=NEVER \
   -DCMAKE_FIND_USE_CMAKE_SYSTEM_PATH=NO \
   -DSFCGAL_CONFIG=disabled \
   -DHDF5_C_COMPILER_EXECUTABLE=disabled \
   -DHDF5_CXX_COMPILER_EXECUTABLE=disabled \
   -DBUILD_SHARED_LIBS=OFF \
   -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
   -DGDAL_BUILD_OPTIONAL_DRIVERS=OFF \
   -DOGR_BUILD_OPTIONAL_DRIVERS=OFF \
   -DGDAL_USE_SQLITE3=ON \
   -DOGR_ENABLE_DRIVER_SQLITE=ON \
   -DOGR_ENABLE_DRIVER_GPKG=ON \
   -DGDAL_ENABLE_DRIVER_GPKG=ON \
   -DOGR_ENABLE_DRIVER_DXF=ON \
   -DOGR_ENABLE_DRIVER_LIBKML=ON \
   -DCMAKE_C_FLAGS="$CFLAGS" \
   -DCMAKE_CXX_FLAGS="$CXXFLAGS" \
   -DCMAKE_EXE_LINKER_FLAGS="$LDFLAGS $PREFIX/lib/libexpat.a $PREFIX/lib/liburiparser.a $PREFIX/lib/libkmlbase.a" \
   -DGDAL_USE_LIBKML=ON  \
   -DLIBKML_INCLUDE_DIR=$PREFIX/include/ \
   -DLIBKML_BASE_LIBRARY=$PREFIX/lib/libkmlbase.a \
   -DLIBKML_DOM_LIBRARY=$PREFIX/lib/libkmldom.a \
   -DLIBKML_ENGINE_LIBRARY=$PREFIX/lib/libkmlengine.a \
   -DLIBEXPAT_LIBRARY=$PREFIX/lib/libexpat.a \
   -DMINIZIP_LIBRARY=$PREFIX/lib/libminizip.a

  echo "--------GDAL make--------"
  make -j$(nproc)
  echo "--------GDAL make install--------"
  make install
  echo "--------GDAL end ($ABI)--------"
}

# install_one_abi <ABI>：把 $PREFIX/lib 下产出的 .a 直接拷进 jniLibs/<abi>/（正确位置）
install_one_abi() {
  export ABI=$1
  export PREFIX=$EXPORT_DIR/third_party-$ABI
  local dest="$JNI_LIBS_DIR/$ABI"
  mkdir -p "$dest"

  echo "==================== INSTALL ABI=$ABI -> $dest ===================="
  for lib in "${LIBS[@]}"; do
    if [ -f "$PREFIX/lib/$lib" ]; then
      cp -f "$PREFIX/lib/$lib" "$dest/$lib"
      echo "  [OK]      $lib"
    else
      echo "  [MISSING] $lib  —— 未在 $PREFIX/lib 找到，请检查该库构建是否成功"
    fi
  done
}

# install_headers：把首个 ABI 前缀里的三方公共头刷进模块 cpp/include/
#   子目录布局与代码 #include 约定一致：gdal/ proj/ sqlite/ curl/ openssl/ + 根 zlib.h/zconf.h；
#   stb/、earcut.cpp 属仓库自带基线（见 include/ 初始化），不在刷新范围。
install_headers() {
  local src=$EXPORT_DIR/third_party-${ABIS[0]}/include
  local dst=$REPO_ROOT/worldwindjni/src/main/cpp/include
  [ -d "$src" ] || { echo "[headers] 跳过：$src 不存在"; return 0; }
  mkdir -p "$dst"
  local f b
  for f in "$src"/*.h; do
    [ -e "$f" ] || continue
    b=$(basename "$f")
    case "$b" in
      zlib.h|zconf.h)              cp -f "$f" "$dst/" ;;
      proj.h|geodesic.h|proj_*.h)  mkdir -p "$dst/proj";   cp -f "$f" "$dst/proj/" ;;
      sqlite3.h|sqlite3ext.h)      mkdir -p "$dst/sqlite"; cp -f "$f" "$dst/sqlite/" ;;
      expat*.h)                    : ;;   # 模块不经顶层引用 expat，避免扫进 gdal/
      *)                           mkdir -p "$dst/gdal";   cp -f "$f" "$dst/gdal/" ;;
    esac
  done
  [ -d "$src/gdal" ]    && { mkdir -p "$dst/gdal";    cp -rf "$src/gdal/."    "$dst/gdal/"; }
  [ -d "$src/proj" ]    && { mkdir -p "$dst/proj";    cp -rf "$src/proj/."    "$dst/proj/"; }
  [ -d "$src/curl" ]    && { mkdir -p "$dst/curl";    cp -rf "$src/curl/."    "$dst/curl/"; }
  [ -d "$src/openssl" ] && { mkdir -p "$dst/openssl"; cp -rf "$src/openssl/." "$dst/openssl/"; }
  echo "[headers] 刷新三方头 -> $dst （gdal/proj/sqlite/curl/openssl/zlib）"
}

# install_proj_data：把 PROJ 编译安装的运行期数据（proj.db + 格网改正数等）拷进模块 assets/proj/。
# 该目录内容与 include/ 生成的三方头、jniLibs/ 的 .a 同属编译产物，不入仓（见 .gitignore），
# 由本步骤在构建时生成；PROJ 数据与架构无关，取首个 ABI 前缀即可。
install_proj_data() {
  local src=$EXPORT_DIR/third_party-${ABIS[0]}/share/proj
  local dst=$REPO_ROOT/worldwindjni/src/main/assets/proj
  if [ ! -d "$src" ]; then
    echo "[proj-data] 跳过：$src 不存在（PROJ 是否构建成功？）"
    return 0
  fi
  mkdir -p "$dst"
  cp -rf "$src/." "$dst/"
  echo "[proj-data] 刷新 PROJ 数据 -> $dst （proj.db 及格网改正数）"
}

# ---- 逐 ABI 构建，全部成功后统一落位 ----
# SKIP_BUILD=1：跳过编译，仅用已存在的 export 产物执行落位(.a→jniLibs)与头刷新(include/)
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  for abi in "${ABIS[@]}"; do
    build_one_abi "$abi"
  done
fi

for abi in "${ABIS[@]}"; do
  install_one_abi "$abi"
done

# 三方头刷新到模块 include/（.a 已落 jniLibs/，头与之配套）
install_headers

# PROJ 运行期数据刷新到模块 assets/proj/（随模块 AAR 分发，不入仓）
install_proj_data

echo "======== 全部 ABI 构建并落位完成：$JNI_LIBS_DIR ========"