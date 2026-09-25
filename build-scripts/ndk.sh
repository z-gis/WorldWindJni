# NDK 期望版本与 Linux 版下载地址（Google 官方仓库；r23b 与本项目迭代期一致，
# 仅为编译期工具链，与模块自身构建用的 NDK 28.x 不冲突，静态库 ABI 兼容）
NDK_VERSION=r23b
NDK_DIR_NAME=android-ndk-$NDK_VERSION
NDK_LINUX_URL=https://dl.google.com/android/repository/android-ndk-$NDK_VERSION-linux.zip
# NDK 下载/解压的专用目录（依赖 MAIN_DIR）；zip 与解压出的 NDK 都落在此，避免污染当前/脚本目录
NDK_INSTALL_DIR=${NDK_INSTALL_DIR:-$MAIN_DIR/ndk}

# 校验候选目录是否为可用的 Linux 版 NDK（不仅看 bin 目录存在，更要 clang 真能执行——
# 在 Windows 挂载盘 9p 上解压会丢符号链接，bin/clang 无法解析，必须拦下）
ndk_valid() {
  [ -n "$1" ] && [ -d "$1/toolchains/llvm/prebuilt/linux-x86_64/bin" ] \
    && "$1/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" --version >/dev/null 2>&1
}

# 解析顺序：1) 外部显式 NDK_ROOT；2) 本地常见位置探测（含 ndk/ 目录）；3) 都没有 → 下载 Linux 版
RESOLVED_NDK=""
if ndk_valid "$NDK_ROOT"; then
  RESOLVED_NDK="$NDK_ROOT"
  echo "使用外部指定的 NDK_ROOT: $RESOLVED_NDK"
else
  for cand in \
    "$NDK_INSTALL_DIR/$NDK_DIR_NAME" \
    "$MAIN_DIR/$NDK_DIR_NAME" \
    "$ANDROID_NDK_HOME" \
    "$ANDROID_NDK_ROOT" \
    "${ANDROID_HOME:+$ANDROID_HOME/ndk-bundle}" \
    "${ANDROID_SDK_ROOT:+$ANDROID_SDK_ROOT/ndk-bundle}"; do
    if ndk_valid "$cand"; then
      RESOLVED_NDK="$cand"
      echo "本地已存在 NDK: $RESOLVED_NDK"
      break
    fi
  done
fi

# 本地未找到：下载并解压 Linux 版 NDK 到 ndk/ 目录
if [ -z "$RESOLVED_NDK" ]; then
  mkdir -p "$NDK_INSTALL_DIR"
  ZIP="$NDK_INSTALL_DIR/$NDK_DIR_NAME-linux.zip"
  echo "未找到本地 NDK，下载 Linux 版：$NDK_LINUX_URL"
  if [ -f "$ZIP" ]; then
    echo "已存在安装包，跳过下载：$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -q --show-progress -O "$ZIP" "$NDK_LINUX_URL"
  elif command -v curl >/dev/null 2>&1; then
    curl -fL -o "$ZIP" "$NDK_LINUX_URL"
  else
    echo "错误：需要 wget 或 curl 以下载 NDK"
    exit 1
  fi
  if ! command -v unzip >/dev/null 2>&1; then
    echo "错误：需要 unzip 以解压 NDK（sudo apt install unzip）"
    exit 1
  fi
  echo "解压 NDK 到 $NDK_INSTALL_DIR ..."
  rm -rf "$NDK_INSTALL_DIR/$NDK_DIR_NAME"
  unzip -q "$ZIP" -d "$NDK_INSTALL_DIR"
  RESOLVED_NDK="$NDK_INSTALL_DIR/$NDK_DIR_NAME"
  if ! ndk_valid "$RESOLVED_NDK"; then
    echo "错误：解压后未找到有效 NDK：$RESOLVED_NDK"
    exit 1
  fi
fi

export NDK_ROOT="$RESOLVED_NDK"
export TOOLCHAIN=$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64
echo "NDK_ROOT=$NDK_ROOT"

# 冒烟测试：clang 必须真能执行，否则多为把 NDK 解压到了 Windows 挂载盘（9p/drvfs，符号链接丢失）
if ! "$TOOLCHAIN/bin/clang" --version >/dev/null 2>&1; then
  echo "错误：NDK clang 无法执行：$TOOLCHAIN/bin/clang"
  echo "      多因在 WSL 把 NDK 解压到 Windows 挂载盘（/mnt/...，9p），符号链接/可执行位丢失。"
  echo "      解决：改用 WSL 原生目录（ext4）作构建工作区后重跑，例如："
  echo "        MAIN_DIR=\$HOME/android-jni-build bash \"$BASE_PATH/build-all.sh\""
  echo "      （build-all.sh 在检测到 /mnt 挂载盘时已会自动做此重定向）"
  exit 1
fi