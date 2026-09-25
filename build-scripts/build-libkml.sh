
LIBKML_VERSION=1.3.0
if [ ! -f libkml-${LIBKML_VERSION}.tar.gz ]; then
  wget https://github.com/libkml/libkml/archive/refs/tags/${LIBKML_VERSION}.tar.gz -O libkml-${LIBKML_VERSION}.tar.gz
fi

rm -rf libkml-${LIBKML_VERSION}
tar xzf libkml-${LIBKML_VERSION}.tar.gz
cd libkml-${LIBKML_VERSION}
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
  -DEXPAT_INCLUDE_DIR=$PREFIX/include \
  -DEXPAT_LIBRARY=$PREFIX/lib/libexpat.a \
  -DMINIZIP_INCLUDE_DIR=$PREFIX/include \
  -DMINIZIP_LIBRARY=$PREFIX/lib/libminizip.a \
  -DURIPARSER_INCLUDE_DIR=$PREFIX/include \
  -DURIPARSER_LIBRARY=$PREFIX/lib/liburiparser.a \
  -DBOOST_ROOT=$PREFIX \
  -DBoost_INCLUDE_DIR=$PREFIX/include
make -j$(nproc)
make install
cd $MAIN_DIR
echo "libkml done"