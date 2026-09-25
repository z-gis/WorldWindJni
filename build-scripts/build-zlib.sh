
#!/bin/bash
# Compile zlib for android with NDK —— 一次构建 x86_64 + arm64-v8a，libz.a 直接落位到 worldwindjni/jniLibs。
# Copyright (C) 2018  shishuo <shishuo365@126.com>
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

# 目标 ABI：目录名须与 ANDROID_ABI 完全一致（x86_64 是下划线，非 x86-64）
APP_ABI=(x86_64 arm64-v8a)

BASE_PATH=$(
	cd "$(dirname $0)"
	pwd
)

ZLIB_PATH="$BASE_PATH/zlib"
BUILD_PATH="$BASE_PATH/build"

# libz.a 落位目标（worldwindjni 模块 jniLibs）；可用环境变量覆盖
JNI_LIBS_DIR=${JNI_LIBS_DIR:-/mnt/k/dev/MobileMap/WorldWindJni/worldwindjni/src/main/cpp/jniLibs}

API=${API:-24}

checkExitCode() {
	if [ $1 -ne 0 ]; then
		echo "Error building zlib library"
		cd $BASE_PATH
		exit $1
	fi
}
safeMakeDir() {
	if [ ! -x "$1" ]; then
		mkdir -p "$1"
	fi
}

## Android NDK
export NDK_ROOT="$NDK_ROOT"

if [ -z "$NDK_ROOT" ]; then
	echo "Please set your NDK_ROOT environment variable first"
	exit 1
fi

## Clean build directory
rm -rf $BUILD_PATH/zlib
safeMakeDir $BUILD_PATH/zlib

## Build zlib

# backup config
cp $ZLIB_PATH/configure $ZLIB_PATH/configure.bak
checkExitCode $?

compatibleWithAndroid() {
	sed 's/case \"$uname\" in/case "_" in/' $ZLIB_PATH/configure >$ZLIB_PATH/configure.temp
	mv $ZLIB_PATH/configure.temp $ZLIB_PATH/configure
	chmod 755 $ZLIB_PATH/configure
}

# compile $1 ABI $2 SYSROOT $3 TOOLCHAIN(bin) $4 TARGET $5 CFLAGS
compile() {
	cd $ZLIB_PATH
	ABI=$1
	SYSROOT=$2
	TOOLCHAIN=$3
	TARGET=$4
	CFLAGS=$5
	export API=$API
	export CC=$TOOLCHAIN/$TARGET$API-clang
	export AS=$CC
	export AR=$TOOLCHAIN/llvm-ar
	export LD=$TOOLCHAIN/ld
	export RANLIB=$TOOLCHAIN/llvm-ranlib
	export STRIP=$TOOLCHAIN/llvm-strip
	export CFLAGS="-I$SYSROOT/usr/include --sysroot=$SYSROOT $CFLAGS"
	export CROSS_PREFIX="$TOOLCHAIN/$TARGET-"
	safeMakeDir $BUILD_PATH/zlib/$ABI
	compatibleWithAndroid
	./configure --prefix=$BUILD_PATH/zlib/$ABI
	checkExitCode $?
	make clean
	checkExitCode $?
	make -j4
	checkExitCode $?
	make install
	checkExitCode $?
	# 落位：libz.a -> jniLibs/<abi>/
	safeMakeDir "$JNI_LIBS_DIR/$ABI"
	cp -f $BUILD_PATH/zlib/$ABI/lib/libz.a "$JNI_LIBS_DIR/$ABI/libz.a"
	checkExitCode $?
	echo "zlib installed: $JNI_LIBS_DIR/$ABI/libz.a"
}

# check system
host=$(uname | tr 'A-Z' 'a-z')
if [ $host = "darwin" ] || [ $host = "linux" ]; then
	echo "system: $host"
else
	echo "unsupport system, only support Mac OS X and Linux now."
	exit 1
fi

TOOLCHAIN_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/$host-x86_64/bin"
SYSROOT_DIR="$NDK_ROOT/toolchains/llvm/prebuilt/$host-x86_64/sysroot"

for abi in ${APP_ABI[*]}; do
	case $abi in
	arm64-v8a)
		compile $abi "$SYSROOT_DIR" "$TOOLCHAIN_BIN" "aarch64-linux-android" "-march=armv8-a -fPIC"
		;;
	x86_64)
		compile $abi "$SYSROOT_DIR" "$TOOLCHAIN_BIN" "x86_64-linux-android" "-march=x86-64 -fPIC"
		;;
	*)
		echo "Error APP_ABI"
		exit 1
		;;
	esac
done

# resume config
mv $ZLIB_PATH/configure.bak $ZLIB_PATH/configure
checkExitCode $?

cd $BASE_PATH
exit 0