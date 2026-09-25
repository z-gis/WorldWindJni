
if [ ! -f sqlite-autoconf-3370200.tar.gz ]; then
  wget -q https://sqlite.org/2022/sqlite-autoconf-3370200.tar.gz
# else
#   rm -f sqlite-autoconf-3370200
fi

rm -rf sqlite-autoconf-3370200

tar xzf sqlite-autoconf-3370200.tar.gz
cd sqlite-autoconf-3370200

./configure \
  --prefix="$PREFIX" \
  --host="$HOST" \
  CC="$CC" CXX="$CXX"  \
  CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS"

make -j$(nproc)
make install
cd $MAIN_DIR