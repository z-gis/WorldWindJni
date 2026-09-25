
# Build proj、
#如果存在build目录，先删除
if [ -d "proj-9.0.0/build" ]; then
    rm -rf proj-9.0.0/build
fi

if [ ! -f proj-9.0.0.tar.gz ]; then
    wget -q https://download.osgeo.org/proj/proj-9.0.0.tar.gz
fi
tar xzf proj-9.0.0.tar.gz
cd proj-9.0.0
mkdir build
cd build

# See later comment in GDAL build section about MAKE_FIND_ROOT_PATH_MODE_INCLUDE, CMAKE_FIND_ROOT_PATH_MODE_LIBRARY
cmake .. \
  -DUSE_CCACHE=ON \
  -DENABLE_TIFF=OFF -DENABLE_CURL=OFF -DBUILD_APPS=OFF -DBUILD_TESTING=OFF \
  -DCMAKE_INSTALL_PREFIX=$PREFIX \
  -DCMAKE_SYSTEM_NAME=Android \
  -DCMAKE_ANDROID_NDK=$NDK_ROOT \
  -DCMAKE_ANDROID_ARCH_ABI=$ABI \
  -DCMAKE_SYSTEM_VERSION=$API \
  "-DCMAKE_PREFIX_PATH=$PREFIX;$TOOLCHAIN/sysroot/usr/" \
  -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=NEVER \
  -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=NEVER \
  -DEXE_SQLITE3=/usr/bin/sqlite3 \
  -DBUILD_SHARED_LIBS=OFF \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DCMAKE_C_FLAGS="$CFLAGS" \
  -DCMAKE_CXX_FLAGS="$CXXFLAGS" \
  -DCMAKE_EXE_LINKER_FLAGS="$LDFLAGS"
make -j$(nproc)
make install
cd $MAIN_DIR