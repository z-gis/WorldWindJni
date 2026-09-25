# build-scripts

worldwindjni native 构建所需的第三方静态库（`.a`）**编译脚本**（源自本项目迭代期的
`GdalDemo/gdal-build` 构建工作区，仅收录脚本，不含源码包与编译产物）。

一次 `bash build-all.sh` 即**同时构建 x86_64 与 arm64-v8a 两个 ABI**，按依赖顺序：

```
expat → zlib/minizip → uriparser → openssl → curl → boost 头 → libkml → sqlite3 → proj → gdal
```

| 脚本 | 作用 |
|---|---|
| `build-all.sh` | 总入口：双 ABI 循环构建 + 落位 `.a`→`jniLibs/`、刷新三方头→`include/`、PROJ 数据→`assets/proj/` |
| `ndk.sh` | 探测/自动下载 NDK r23b，校验 clang 工具链可用 |
| `cmake.sh` | 探测/自动下载 Linux 版 cmake 并加入 PATH |
| `build-expat.sh` | expat 2.4.8 |
| `build-minizip.sh` | zlib 1.2.13 + minizip（zlib contrib） |
| `build-uriparser.sh` | uriparser 0.9.8 |
| `build-openssl.sh` | OpenSSL 1.1.1w（静态，供 curl 打包） |
| `build-curl.sh` | curl 8.4.0，并把 ssl/crypto 合并进 `libcurl.a` |
| `build-libkml.sh` | libkml 1.3.0（需 boost 头文件） |
| `build-sqlite3.sh` | sqlite-autoconf 3.37.2 |
| `build-proj.sh` | PROJ 9.0.0 |

GDAL 3.7.0 的构建配置内联在 `build-all.sh` 末尾（CMake 参数含 libkml/expat/minizip 挂接，
可选驱动已裁剪）。

关键行为：
- **WSL 挂载盘自动重定向**：脚本位于 `/mnt/<盘>`（9p/drvfs）时，构建工作区自动改用原生
  ext4 目录 `~/android-jni-build`，产物仍按仓库路径落回 `jniLibs/`、`include/`、`assets/proj/`。
- **离线复用**：`PKG_CACHE`（默认 `build-scripts/third-party`）里的 `*.tar.gz`/`*.zip` 与
  `boost/` 头会被 `cp -n` 种入工作区，避免重复下载。
- `SKIP_BUILD=1 bash build-all.sh`：跳过编译，仅用已存在的 `export` 产物执行落位与刷新。

运行环境（WSL2/Linux + NDK r23b）、产物清单与完整的 0→1 步骤见仓库根
[README.md](../README.md) 的「0 → 1 从零构建」章节。`.a`、生成的三方头与 PROJ 数据均不入版本库
（见根 `.gitignore`）。
