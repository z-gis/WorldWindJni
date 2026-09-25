export URIPARSER_VER=0.9.8

if [ ! -f uriparser-$URIPARSER_VER.tar.gz ]; then
  wget https://github.com/uriparser/uriparser/releases/download/uriparser-$URIPARSER_VER/uriparser-$URIPARSER_VER.tar.gz
fi

rm -rf uriparser-$URIPARSER_VER
tar xzf uriparser-$URIPARSER_VER.tar.gz
cd uriparser-$URIPARSER_VER
rm -rf build-android-$ABI
mkdir build-android-$ABI && cd build-android-$ABI
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$NDK_ROOT/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=$ABI \
  -DANDROID_PLATFORM=android-$API \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DCMAKE_INSTALL_PREFIX=$PREFIX \
  -DURIPARSER_BUILD_TESTS=OFF \
  -DURIPARSER_BUILD_DOCS=OFF
make -j$(nproc)
make install
cd $MAIN_DIR
echo "uriparser done"