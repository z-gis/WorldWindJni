
export EXPAT_VER=2.4.8

if [ ! -f expat-$EXPAT_VER.tar.gz ]; then
  wget https://github.com/libexpat/libexpat/releases/download/R_${EXPAT_VER//./_}/expat-$EXPAT_VER.tar.gz
fi

#删除目录
rm -rf expat-$EXPAT_VER
#解压
tar xzf expat-$EXPAT_VER.tar.gz

cd expat-$EXPAT_VER

rm -rf build-android-$ABI

mkdir build-android-$ABI && cd build-android-$ABI

cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$NDK_ROOT/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=$ABI \
  -DANDROID_PLATFORM=android-$API \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DCMAKE_INSTALL_PREFIX=$PREFIX
make -j$(nproc)
make install
cd $MAIN_DIR
echo " "
echo "expat done ---------------------------------------"
echo " "